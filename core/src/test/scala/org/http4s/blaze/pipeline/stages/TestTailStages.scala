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

package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.TailStage

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.Promise

class MapTail[A](f: A => A) extends TailStage[A] {
  override def name = "MapTail"

  def startLoop(): Future[Unit] = {
    val p = Promise[Unit]()
    innerLoop(p)
    p.future
  }

  private def innerLoop(p: Promise[Unit]): Unit =
    channelRead(-1, 10.seconds)
      .flatMap(a => channelWrite(f(a)))
      .onComplete {
        case Success(_) => innerLoop(p)
        case Failure(EOF) => p.success(())
        case e => p.complete(e)
      }
}

class EchoTail[A] extends MapTail[A](identity)
