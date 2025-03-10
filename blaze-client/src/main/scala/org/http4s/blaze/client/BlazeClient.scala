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

import cats.Applicative
import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.http4s.blaze.core.ResponseHeaderTimeoutStage
import org.http4s.blaze.util.TickWheelExecutor
import org.http4s.client.Client
import org.http4s.client.DefaultClient
import org.http4s.client.RequestKey
import org.http4s.client.UnexpectedStatus
import org.http4s.client.middleware.Retry
import org.http4s.client.middleware.RetryPolicy

import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Blaze client implementation */
object BlazeClient {
  private[blaze] def makeClient[F[_], A <: BlazeConnection[F]](
      manager: ConnectionManager[F, A],
      responseHeaderTimeout: Duration,
      requestTimeout: Duration,
      scheduler: TickWheelExecutor,
      ec: ExecutionContext,
      retries: Int,
      dispatcher: Dispatcher[F],
  )(implicit F: Async[F]): Client[F] = {
    val base = new BlazeClient[F, A](
      manager,
      responseHeaderTimeout,
      requestTimeout,
      scheduler,
      ec,
      dispatcher,
    )
    if (retries > 0)
      Retry(retryPolicy(retries))(base)
    else
      base
  }

  private[this] val retryNow = Duration.Zero.some
  private def retryPolicy[F[_]](retries: Int): RetryPolicy[F] = { (req, result, n) =>
    result match {
      case Left(_: SocketException) if n <= retries && req.isIdempotent => retryNow
      case _ => None
    }
  }
}

private class BlazeClient[F[_], A <: BlazeConnection[F]](
    manager: ConnectionManager[F, A],
    responseHeaderTimeout: Duration,
    requestTimeout: Duration,
    scheduler: TickWheelExecutor,
    ec: ExecutionContext,
    dispatcher: Dispatcher[F],
)(implicit F: Async[F])
    extends DefaultClient[F] {

  override def run(req: Request[F]): Resource[F, Response[F]] = {
    val key = RequestKey.fromRequest(req)
    for {
      requestTimeoutF <- scheduleRequestTimeout(key)
      preparedConnection <- prepareConnection(key)
      (conn, responseHeaderTimeoutF) = preparedConnection
      timeout = responseHeaderTimeoutF.race(requestTimeoutF).map(_.merge)
      responseResource <- Resource.eval(runRequest(conn, req, timeout))
      response <- responseResource
    } yield response
  }

  override def defaultOnError(req: Request[F])(resp: Response[F])(implicit
      G: Applicative[F]
  ): F[Throwable] =
    resp.body.compile.drain.as(UnexpectedStatus(resp.status, req.method, req.uri))

  private def prepareConnection(key: RequestKey): Resource[F, (A, F[TimeoutException])] = for {
    conn <- borrowConnection(key)
    responseHeaderTimeoutF <- addResponseHeaderTimeout(conn)
  } yield (conn, responseHeaderTimeoutF)

  private def borrowConnection(key: RequestKey): Resource[F, A] =
    Resource.makeCase(manager.borrow(key).map(_.connection)) {
      case (conn, ExitCase.Canceled) =>
        // Currently we can't just release in case of cancellation, because cancellation clears the Write state of Http1Connection, so it might result in isRecycle=true even if there's a half-written request.
        manager.invalidate(conn)
      case (conn, _) => manager.release(conn)
    }

  private def addResponseHeaderTimeout(conn: A): Resource[F, F[TimeoutException]] =
    responseHeaderTimeout match {
      case d: FiniteDuration =>
        Resource.apply(
          Deferred[F, Either[Throwable, TimeoutException]].flatMap(timeout =>
            F.delay {
              val stage = new ResponseHeaderTimeoutStage[ByteBuffer](d, scheduler, ec)
              conn.spliceBefore(stage)
              stage.init(e => dispatcher.unsafeRunSync(timeout.complete(e).void))
              (timeout.get.rethrow, F.delay(stage.removeStage()))
            }
          )
        )
      case _ => resourceNeverTimeoutException
    }

  private def scheduleRequestTimeout(key: RequestKey): Resource[F, F[TimeoutException]] =
    requestTimeout match {
      case d: FiniteDuration =>
        Resource.pure(F.async[TimeoutException] { cb =>
          F.delay(
            scheduler.schedule(
              () =>
                cb(
                  Right(new TimeoutException(s"Request to $key timed out after ${d.toMillis} ms"))
                ),
              ec,
              d,
            )
          ).map(c => Some(F.delay(c.cancel())))
        })
      case _ => resourceNeverTimeoutException
    }

  private def runRequest(
      conn: A,
      req: Request[F],
      timeout: F[TimeoutException],
  ): F[Resource[F, Response[F]]] =
    conn
      .runRequest(req, timeout)
      .race(timeout.flatMap(F.raiseError[Resource[F, Response[F]]](_)))
      .map(_.merge)

  private val resourceNeverTimeoutException = Resource.pure[F, F[TimeoutException]](F.never)

}
