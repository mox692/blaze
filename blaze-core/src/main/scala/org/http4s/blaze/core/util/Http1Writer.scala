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

import cats.effect.kernel.Outcome
import cats.effect.syntax.monadCancel._
import cats.syntax.all._
import org.http4s.Entity
import org.http4s.util.StringWriter
import org.log4s.getLogger

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent._

private[blaze] trait Http1Writer[F[_]] extends EntityBodyWriter[F] {
  final def write(headerWriter: StringWriter, entity: Entity[F]): F[Boolean] =
    fromFutureNoShift(F.delay(writeHeaders(headerWriter)))
      .guaranteeCase {
        case Outcome.Succeeded(_) =>
          F.unit

        case Outcome.Errored(_) | Outcome.Canceled() =>
          entity match {
            case Entity.Default(body, _) =>
              body.compile.drain.handleError { t2 =>
                Http1Writer.logger.error(t2)("Error draining body")
              }
            case Entity.Strict(_) | Entity.Empty => F.unit
          }
      } >> writeEntityBody(entity)

  /* Writes the header.  It is up to the writer whether to flush immediately or to
   * buffer the header with a subsequent chunk. */
  def writeHeaders(headerWriter: StringWriter): Future[Unit]
}

private[util] object Http1Writer {
  private val logger = getLogger

  def headersToByteBuffer(headers: String): ByteBuffer =
    ByteBuffer.wrap(headers.getBytes(StandardCharsets.ISO_8859_1))
}
