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

package org.http4s.blaze.core.util

import cats.Applicative
import cats.effect._
import cats.syntax.all._
import fs2._
import org.http4s.Entity
import org.http4s.blaze.pipeline._
import org.http4s.util.StringWriter

import java.nio.ByteBuffer
import scala.concurrent._

/** Discards the body, killing it so as to clean up resources
  *
  * @param pipe the blaze `TailStage`, which takes ByteBuffers which will send the data downstream
  */
private[blaze] class BodylessWriter[F[_]](pipe: TailStage[ByteBuffer], close: Boolean)(implicit
    protected val F: Async[F]
) extends Http1Writer[F] {
  def writeHeaders(headerWriter: StringWriter): Future[Unit] =
    pipe.channelWrite(Http1Writer.headersToByteBuffer(headerWriter.result))

  /** Doesn't write the entity body, just the headers. Kills the stream, if an error if necessary
    *
    * @param entity an [[Entity]] which body will be killed
    * @return the F which, when run, will send the headers and kill the entity body
    */
  override def writeEntityBody(entity: Entity[F]): F[Boolean] =
    entity match {
      case Entity.Default(body, _) =>
        body.compile.drain.as(close)

      case Entity.Strict(_) | Entity.Empty =>
        Applicative[F].pure(close)
    }

  override protected def writeEnd(chunk: Chunk[Byte]): Future[Boolean] =
    Future.successful(close)

  override protected def writeBodyChunk(chunk: Chunk[Byte], flush: Boolean): Future[Unit] =
    FutureUnit
}
