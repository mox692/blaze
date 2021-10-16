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

package org.http4s.blaze
package client

import cats.implicits._
import cats.effect.implicits._
import cats.effect._
import fs2.Stream
import org.http4s.Method._
import org.http4s.client.JettyScaffold.JettyTestServer
import org.http4s._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

class BlazeClientConnectionReuseSuite extends BlazeClientBase {
  override def munitTimeout: Duration = new FiniteDuration(50, TimeUnit.SECONDS)

  test("BlazeClient should reuse the connection after a simple successful request") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient should reuse the connection after a successful request with large response") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "large"))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient.status shouldn't wait for the response entity, nonetheless it may reuse the connection if the response entity has already been fully read") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.status(Request[IO](GET, servers(0).uri / "simple"))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient.status shouldn't wait for the response entity and shouldn't reuse the connection if the response entity hasn't been fully read") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client
          .status(Request[IO](GET, servers(0).uri / "infinite"))
          .timeout(5.seconds) // we expect it to complete without waiting for the response body
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(2)
      } yield ()
    }
  }

  test("BlazeClient should reuse connections to different servers separately") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
        _ <- servers(1).numberOfEstablishedConnections.assertEquals(0)
        _ <- client.expect[String](Request[IO](GET, servers(1).uri / "simple"))
        _ <- client.expect[String](Request[IO](GET, servers(1).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
        _ <- servers(1).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  //// Decoding failures ////

  test("BlazeClient should reuse the connection after response decoding failed") {
    // This will work regardless of whether we drain the entity or not,
    // because the response is small and it is read in full in first read operation
    val drainThenFail = EntityDecoder.error[IO, String](new Exception())
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client
          .expect[String](Request[IO](GET, servers(0).uri / "simple"))(drainThenFail)
          .attempt
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient should reuse the connection after response decoding failed and the (large) entity was drained") {
    val drainThenFail = EntityDecoder.error[IO, String](new Exception())
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client
          .expect[String](Request[IO](GET, servers(0).uri / "large"))(drainThenFail)
          .attempt
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient shouldn't reuse the connection after response decoding failed and the (large) entity wasn't drained") {
    val failWithoutDraining = new EntityDecoder[IO, String] {
      override def decode(m: Media[IO], strict: Boolean): DecodeResult[IO, String] =
        DecodeResult[IO, String](IO.raiseError(new Exception()))
      override def consumes: Set[MediaRange] = Set.empty
    }
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client
          .expect[String](Request[IO](GET, servers(0).uri / "large"))(failWithoutDraining)
          .attempt
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(2)
      } yield ()
    }
  }

  //// Requests with an entity ////

  test("BlazeClient should reuse the connection after a request with an entity") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.expect[String](
          Request[IO](POST, servers(0).uri / "process-request-entity").withEntity("entity"))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(1)
      } yield ()
    }
  }

  test(
    "BlazeClient shouldn't wait for the request entity transfer to complete if the server closed the connection early. The closed connection shouldn't be reused.") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client.expect[String](
          Request[IO](POST, servers(0).uri / "respond-and-close-immediately")
            .withBodyStream(Stream(0.toByte).repeat))
        _ <- client.expect[String](Request[IO](GET, servers(0).uri / "simple"))
        _ <- servers(0).numberOfEstablishedConnections.assertEquals(2)
      } yield ()
    }
  }

  //// Load tests ////

  test(
    "BlazeClient should keep reusing connections even when under heavy load (single client scenario)") {
    builder().resource.use { client =>
      for {
        servers <- makeServers()
        _ <- client
          .expect[String](Request[IO](GET, servers(0).uri / "simple"))
          .replicateA(200)
          .parReplicateA(20)
        // There's no guarantee we'll actually manage to use 20 connections in parallel. Sharing the client means sharing the lock inside PoolManager as a contention point.
        _ <- servers(0).numberOfEstablishedConnections.map(_ <= 20).assert
      } yield ()
    }
  }

  test(
    "BlazeClient should keep reusing connections even when under heavy load (multiple clients scenario)") {
    for {
      servers <- makeServers()
      _ <- builder().resource
        .use { client =>
          client.expect[String](Request[IO](GET, servers(0).uri / "simple")).replicateA(400)
        }
        .parReplicateA(20)
      _ <- servers(0).numberOfEstablishedConnections.assertEquals(20)
    } yield ()
  }

  private def builder(): BlazeClientBuilder[IO] =
    BlazeClientBuilder[IO](munitExecutionContext).withScheduler(scheduler = tickWheel)

  private def makeServers(): IO[Vector[JettyTestServer]] = {
    val jettyScafold = jettyServer()
    jettyScafold.resetCounters().as(jettyScafold.servers)
  }

  private implicit class ParReplicateASyntax[A](ioa: IO[A]) {
    def parReplicateA(n: Int): IO[List[A]] = List.fill(n)(ioa).parSequence

    def parReplicateAN(n: Int, parallelism: Long): IO[List[A]] =
      List.fill(n)(ioa).parSequenceN(parallelism)
  }
}
