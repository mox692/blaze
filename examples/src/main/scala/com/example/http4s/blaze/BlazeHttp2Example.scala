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

package com.example.http4s
package blaze

import cats.effect._

object BlazeHttp2Example extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    BlazeSslExampleApp.builder[IO].flatMap(_.enableHttp2(true).serve.compile.lastOrError)
}
