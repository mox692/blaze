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

package com.example.http4s.blaze.demo.server

import cats.data.OptionT
import cats.effect._
import cats.syntax.semigroupk._
import com.example.http4s.blaze.demo.server.endpoints._
import com.example.http4s.blaze.demo.server.endpoints.auth.BasicAuthHttpEndpoint
import com.example.http4s.blaze.demo.server.endpoints.auth.GitHubHttpEndpoint
import com.example.http4s.blaze.demo.server.service.FileService
import com.example.http4s.blaze.demo.server.service.GitHubService
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.server.HttpMiddleware
import org.http4s.server.middleware.AutoSlash
import org.http4s.server.middleware.ChunkAggregator
import org.http4s.server.middleware.GZip
import org.http4s.server.middleware.Timeout

import scala.concurrent.duration._

class Module[F[_]: Async](client: Client[F]) {
  private val fileService = new FileService[F]

  private val gitHubService = new GitHubService[F](client)

  def middleware: HttpMiddleware[F] = { (routes: HttpRoutes[F]) =>
    GZip(routes)
  }.compose(routes => AutoSlash(routes))

  val fileHttpEndpoint: HttpRoutes[F] =
    new FileHttpEndpoint[F](fileService).service

  val nonStreamFileHttpEndpoint: HttpRoutes[F] =
    ChunkAggregator(OptionT.liftK[F])(fileHttpEndpoint)

  private val hexNameHttpEndpoint: HttpRoutes[F] =
    new HexNameHttpEndpoint[F].service

  private val compressedEndpoints: HttpRoutes[F] =
    middleware(hexNameHttpEndpoint)

  private val timeoutHttpEndpoint: HttpRoutes[F] =
    new TimeoutHttpEndpoint[F].service

  private val timeoutEndpoints: HttpRoutes[F] =
    Timeout(1.second)(timeoutHttpEndpoint)

  private val multipartHttpEndpoint: HttpRoutes[F] =
    new MultipartHttpEndpoint[F](fileService).service

  private val gitHubHttpEndpoint: HttpRoutes[F] =
    new GitHubHttpEndpoint[F](gitHubService).service

  val basicAuthHttpEndpoint: HttpRoutes[F] =
    new BasicAuthHttpEndpoint[F].service

  val httpServices: HttpRoutes[F] = (
    compressedEndpoints <+> timeoutEndpoints <+> multipartHttpEndpoint
      <+> gitHubHttpEndpoint
  )
}
