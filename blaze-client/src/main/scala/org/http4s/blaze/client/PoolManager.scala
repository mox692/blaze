/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package blaze
package client

import cats.effect._
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.syntax.all._
import org.http4s.client.RequestKey
import org.log4s.getLogger

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

final case class WaitQueueFullFailure() extends RuntimeException {
  override def getMessage: String = "Wait queue is full"
}

/** @param maxIdleDuration the maximum time a connection can be idle
  * and still be borrowed
  */
private final class PoolManager[F[_], A <: Connection[F]](
    builder: ConnectionBuilder[F, A],
    maxTotal: Int,
    maxWaitQueueLimit: Int,
    maxConnectionsPerRequestKey: RequestKey => Int,
    responseHeaderTimeout: Duration,
    requestTimeout: Duration,
    semaphore: Semaphore[F],
    implicit private val executionContext: ExecutionContext,
    maxIdleDuration: Duration,
)(implicit F: Async[F])
    extends ConnectionManager.Stateful[F, A] { self =>

  private sealed case class PooledConnection(conn: A, borrowDeadline: Option[Deadline])

  private sealed case class Waiting(
      key: RequestKey,
      callback: Callback[NextConnection],
      at: Instant,
  )

  private[this] val logger = getLogger

  private var isClosed = false
  private var curTotal = 0
  private val allocated = mutable.Map.empty[RequestKey, Int]
  private val idleQueues = mutable.Map.empty[RequestKey, mutable.Queue[PooledConnection]]
  private var waitQueue = mutable.Queue.empty[Waiting]

  private def stats =
    s"curAllocated=$curTotal idleQueues.size=${idleQueues.size} waitQueue.size=${waitQueue.size} maxWaitQueueLimit=$maxWaitQueueLimit closed=${isClosed}"

  private def getConnectionFromQueue(key: RequestKey): F[Option[PooledConnection]] =
    F.delay {
      idleQueues.get(key).flatMap { q =>
        if (q.nonEmpty) {
          val pooled = q.dequeue()
          if (q.isEmpty) idleQueues.remove(key)
          Some(pooled)
        } else None
      }
    }

  private def incrConnection(key: RequestKey): F[Unit] =
    F.delay {
      curTotal += 1
      allocated.update(key, allocated.getOrElse(key, 0) + 1)
    }

  private def decrConnection(key: RequestKey): F[Unit] =
    F.delay {
      curTotal -= 1
      val numConnections = allocated.getOrElse(key, 0)
      // If there are no more connections drop the key
      if (numConnections == 1) {
        allocated.remove(key)
        idleQueues.remove(key)
        ()
      } else
        allocated.update(key, numConnections - 1)
    }

  private def numConnectionsCheckHolds(key: RequestKey): Boolean =
    curTotal < maxTotal && allocated.getOrElse(key, 0) < maxConnectionsPerRequestKey(key)

  private def isRequestExpired(t: Instant): Boolean = {
    val elapsed = Instant.now().toEpochMilli - t.toEpochMilli
    (requestTimeout.isFinite && elapsed >= requestTimeout.toMillis) || (responseHeaderTimeout.isFinite && elapsed >= responseHeaderTimeout.toMillis)
  }

  /** This method is the core method for creating a connection which increments allocated synchronously
    * then builds the connection with the given callback and completes the callback.
    *
    * If we can create a connection then it initially increments the allocated value within a region
    * that is called synchronously by the calling method. Then it proceeds to attempt to create the connection
    * and feed it the callback. If we cannot create a connection because we are already full then this
    * completes the callback on the error synchronously.
    *
    * @param key      The RequestKey for the Connection.
    * @param callback The callback to complete with the NextConnection.
    */
  private def createConnection(key: RequestKey, callback: Callback[NextConnection]): F[Unit] =
    F.ifM(F.delay(numConnectionsCheckHolds(key)))(
      incrConnection(key) *> F.start {
        builder(key).attempt
          .flatMap {
            case Right(conn) =>
              F.delay(callback(Right(NextConnection(conn, fresh = true))))
            case Left(error) =>
              disposeConnection(key, None) *> F.delay(callback(Left(error)))
          }
          .evalOn(executionContext)
      }.void,
      addToWaitQueue(key, callback),
    )

  private def addToWaitQueue(key: RequestKey, callback: Callback[NextConnection]): F[Unit] =
    F.delay {
      if (waitQueue.length < maxWaitQueueLimit) {
        waitQueue.enqueue(Waiting(key, callback, Instant.now()))
        ()
      } else {
        logger.error(
          s"Max wait queue for limit of $maxWaitQueueLimit for $key reached, not scheduling."
        )
        callback(Left(WaitQueueFullFailure()))
      }
    }

  private def addToIdleQueue(conn: A, key: RequestKey): F[Unit] =
    F.delay {
      val borrowDeadline = maxIdleDuration match {
        case finite: FiniteDuration => Some(Deadline.now + finite)
        case _ => None
      }
      val q = idleQueues.getOrElse(key, mutable.Queue.empty[PooledConnection])
      q.enqueue(PooledConnection(conn, borrowDeadline))
      idleQueues.update(key, q)
    }

  /** This generates a effect of Next Connection. The following calls are executed asynchronously
    * with respect to whenever the execution of this task can occur.
    *
    * If the pool is closed the effect failure is executed.
    *
    * If the pool is not closed then we look for any connections in the idleQueues that match
    * the RequestKey requested.
    * If a matching connection exists and it is still open the callback is executed with the connection.
    * If a matching connection is closed we deallocate and repeat the check through the idleQueues.
    * If no matching connection is found, and the pool is not full we create a new Connection to perform
    * the request.
    * If no matching connection is found and the pool is full, and we have connections in the idleQueues
    * then a connection in the idleQueues is shutdown and a new connection is created to perform the request.
    * If no matching connection is found and the pool is full, and all connections are currently in use
    * then the Request is placed in a waitingQueue to be executed when a connection is released.
    *
    * @param key The Request Key For The Connection
    * @return An effect of NextConnection
    */
  def borrow(key: RequestKey): F[NextConnection] =
    F.async { callback =>
      semaphore.permit.use { _ =>
        if (!isClosed) {
          def go(): F[Unit] =
            getConnectionFromQueue(key).flatMap {
              case Some(pooled) if pooled.conn.isClosed =>
                F.delay(logger.debug(s"Evicting closed connection for $key: $stats")) *>
                  decrConnection(key) *>
                  go()

              case Some(pooled) if pooled.borrowDeadline.exists(_.isOverdue()) =>
                F.delay(
                  logger.debug(s"Shutting down and evicting expired connection for $key: $stats")
                ) *>
                  decrConnection(key) *>
                  F.delay(pooled.conn.shutdown()) *>
                  go()

              case Some(pooled) =>
                F.delay(logger.debug(s"Recycling connection for $key: $stats")) *>
                  F.delay(callback(Right(NextConnection(pooled.conn, fresh = false))))

              case None if numConnectionsCheckHolds(key) =>
                F.delay(
                  logger.debug(s"Active connection not found for $key. Creating new one. $stats")
                ) *>
                  createConnection(key, callback)

              case None if maxConnectionsPerRequestKey(key) <= 0 =>
                F.delay(callback(Left(NoConnectionAllowedException(key))))

              case None if curTotal == maxTotal =>
                val keys = idleQueues.keys
                if (keys.nonEmpty)
                  F.delay(
                    logger.debug(
                      s"No connections available for the desired key, $key. Evicting random and creating a new connection: $stats"
                    )
                  ) *>
                    F.delay(keys.iterator.drop(Random.nextInt(keys.size)).next()).flatMap {
                      randKey =>
                        getConnectionFromQueue(randKey).map(
                          _.fold(
                            logger.warn(s"No connection to evict from the idleQueue for $randKey")
                          )(_.conn.shutdown())
                        ) *>
                          decrConnection(randKey)
                    } *>
                    createConnection(key, callback)
                else
                  F.delay(
                    logger.debug(
                      s"No connections available for the desired key, $key. Adding to waitQueue: $stats"
                    )
                  ) *>
                    addToWaitQueue(key, callback)

              case None => // we're full up. Add to waiting queue.
                F.delay(
                  logger.debug(
                    s"No connections available for $key.  Waiting on new connection: $stats"
                  )
                ) *>
                  addToWaitQueue(key, callback)
            }

          F.delay(logger.debug(s"Requesting connection for $key: $stats")).productR(go()).as(None)
        } else
          F.delay(callback(Left(new IllegalStateException("Connection pool is closed")))).as(None)
      }
    }

  private def releaseRecyclable(key: RequestKey, connection: A): F[Unit] =
    F.delay(waitQueue.dequeueFirst(_.key == key)).flatMap {
      case Some(Waiting(_, callback, at)) =>
        if (isRequestExpired(at))
          F.delay(logger.debug(s"Request expired for $key")) *>
            F.delay(callback(Left(WaitQueueTimeoutException))) *>
            releaseRecyclable(key, connection)
        else
          F.delay(logger.debug(s"Fulfilling waiting connection request for $key: $stats")) *>
            F.delay(callback(Right(NextConnection(connection, fresh = false))))

      case None if waitQueue.isEmpty =>
        F.delay(logger.debug(s"Returning idle connection to pool for $key: $stats")) *>
          addToIdleQueue(connection, key)

      case None =>
        findFirstAllowedWaiter.flatMap {
          case Some(Waiting(k, cb, _)) =>
            // This is the first waiter not blocked on the request key limit.
            // close the undesired connection and wait for another
            F.delay(connection.shutdown()) *>
              decrConnection(key) *>
              createConnection(k, cb)

          case None =>
            // We're blocked not because of too many connections, but
            // because of too many connections per key.
            // We might be able to reuse this request.
            addToIdleQueue(connection, key)
        }
    }

  private def releaseNonRecyclable(key: RequestKey, connection: A): F[Unit] =
    decrConnection(key) *>
      F.delay {
        if (!connection.isClosed) {
          logger.debug(s"Connection returned was busy for $key. Shutting down: $stats")
          connection.shutdown()
        }
      } *>
      findFirstAllowedWaiter.flatMap {
        case Some(Waiting(k, callback, _)) =>
          F.delay(
            logger
              .debug(
                s"Connection returned could not be recycled, new connection needed for $key: $stats"
              )
          ) *>
            createConnection(k, callback)

        case None =>
          F.delay(
            logger.debug(
              s"Connection could not be recycled for $key, no pending requests. Shrinking pool: $stats"
            )
          )
      }

  /** This is how connections are returned to the ConnectionPool.
    *
    * If the pool is closed the connection is shutdown and logged.
    * If it is not closed we check if the connection is recyclable.
    *
    * If the connection is Recyclable we check if any of the connections in the waitQueue
    * are looking for the returned connections RequestKey.
    * If one is the first found is given the connection.And runs it using its callback asynchronously.
    * If one is not found and the waitingQueue is Empty then we place the connection on the idle queue.
    * If the waiting queue is not empty and we did not find a match then we shutdown the connection
    * and create a connection for the first item in the waitQueue.
    *
    * If it is not recyclable, and it is not shutdown we shutdown the connection. If there
    * are values in the waitQueue we create a connection and execute the callback asynchronously.
    * Otherwise the pool is shrunk.
    *
    * @param connection The connection to be released.
    * @return An effect of Unit
    */
  def release(connection: A): F[Unit] = {
    val key = connection.requestKey
    semaphore.permit.use { _ =>
      connection.isRecyclable
        .ifM(releaseRecyclable(key, connection), releaseNonRecyclable(key, connection))
    }
  }

  private def findFirstAllowedWaiter: F[Option[Waiting]] =
    F.delay {
      val (expired, rest) = waitQueue.span(w => isRequestExpired(w.at))
      expired.foreach(_.callback(Left(WaitQueueTimeoutException)))
      if (expired.nonEmpty) {
        logger.debug(s"expired requests: ${expired.length}")
        waitQueue = rest
        logger.debug(s"Dropped expired requests: $stats")
      }
      waitQueue.dequeueFirst { waiter =>
        allocated.getOrElse(waiter.key, 0) < maxConnectionsPerRequestKey(waiter.key)
      }
    }

  /** This invalidates a Connection. This is what is exposed externally, and
    * is just an effect wrapper around disposing the connection.
    *
    * @param connection The connection to invalidate
    * @return An effect of Unit
    */
  override def invalidate(connection: A): F[Unit] =
    semaphore.permit.use { _ =>
      val key = connection.requestKey
      decrConnection(key) *>
        F.delay(if (!connection.isClosed) connection.shutdown()) *>
        findFirstAllowedWaiter.flatMap {
          case Some(Waiting(k, callback, _)) =>
            F.delay(
              logger.debug(s"Invalidated connection for $key, new connection needed: $stats")
            ) *>
              createConnection(k, callback)

          case None =>
            F.delay(
              logger.debug(
                s"Invalidated connection for $key, no pending requests. Shrinking pool: $stats"
              )
            )
        }
    }

  /** Synchronous Immediate Disposal of a Connection and Its Resources.
    *
    * By taking an Option of a connection this also serves as a synchronized allocated decrease.
    *
    * @param key        The request key for the connection. Not used internally.
    * @param connection An Option of a Connection to Dispose Of.
    */
  private def disposeConnection(key: RequestKey, connection: Option[A]): F[Unit] =
    semaphore.permit.use { _ =>
      F.delay(logger.debug(s"Disposing of connection for $key: $stats")) *>
        decrConnection(key) *>
        F.delay {
          connection.foreach { s =>
            if (!s.isClosed) s.shutdown()
          }
        }
    }

  /** Shuts down the connection pool permanently.
    *
    * Changes isClosed to true, no methods can reopen a closed Pool.
    * Shutdowns all connections in the IdleQueue and Sets Allocated to Zero
    *
    * @return An effect Of Unit
    */
  def shutdown: F[Unit] =
    semaphore.permit.use { _ =>
      F.delay {
        logger.info(s"Shutting down connection pool: $stats")
        if (!isClosed) {
          isClosed = true
          idleQueues.foreach(_._2.foreach(_.conn.shutdown()))
          idleQueues.clear()
          allocated.clear()
          curTotal = 0
        }
      }
    }

  def state: BlazeClientState[F] =
    new BlazeClientState[F] {
      def isClosed: F[Boolean] = F.delay(self.isClosed)
      def allocated: F[Map[RequestKey, Int]] = F.delay(self.allocated.toMap)
      def idleQueueDepth: F[Map[RequestKey, Int]] =
        F.delay(self.idleQueues.toMap.view.mapValues(_.size).toMap)
      def waitQueueDepth: F[Int] = F.delay(self.waitQueue.size)
    }
}

final case class NoConnectionAllowedException(key: RequestKey)
    extends IllegalArgumentException(s"No client connections allowed to $key")
