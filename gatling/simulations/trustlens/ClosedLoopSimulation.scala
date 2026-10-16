package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Closed-loop Phase 2 — one continuous run (no stack restart) during which
 * TrustLens recommendations are applied at runtime by closed_loop_controller.py.
 *
 * Timeline (s):
 *     0–1500  legitimate traffic, 300 users/s (login + location update)
 *    30–90    interception of legitimate users' tokens
 *   240–360   C1: impossible travel (business-logic forgery)
 *   540–720   B : replay of the intercepted tokens (> 7 min old)
 *   840–960   C2: impossible travel again
 *  1200–1320  Redis outage (injected externally, not an attack)
 *
 * Attack requests check status.is(200): in simulation.log an OK attack request
 * was ACCEPTED by the system, a KO one was rejected (401/403). Legitimate
 * requests also check 200, so KO legitimate requests measure collateral
 * rejections and the outage.
 */
class ClosedLoopSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")
    // Virtual users share a keep-alive connection pool (API clients behind a
    // gateway); avoids connection-churn resets in the Docker port proxy.
    .shareConnections

  val userFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      // Fixed position per user ID — avoids impossible-travel false positives under p5
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  val legitimate = scenario("LegitimateUser")
    .feed(userFeeder)
    .exec(
      http("login")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
    .exitHereIfFailed
    .exec(
      http("updateLocation")
        .post("/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200))
    )

  // Token interception: regular users' sessions are eavesdropped
  val interception = scenario("TokenInterception")
    .feed(userFeeder)
    .exec(
      http("intercepted-session")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("stolen"))
    )
    .exec { s =>
      CapturedTokens.store(s("stolen").as[String], s("lat").as[Double], s("lon").as[Double])
      s
    }

  // B — replay of intercepted tokens (victim device and position)
  val replay = scenario("CredentialReplay")
    .exec { s =>
      val (t, lat, lon) = CapturedTokens.pick()
      s.set("capturedToken", t).set("lat", lat).set("lon", lon)
    }
    .repeat(2) {
      exec(
        http("B-replay")
          .post("/location/update")
          .queryParam("lat", "#{lat}")
          .queryParam("lon", "#{lon}")
          .header("Authorization", "Bearer #{capturedToken}")
          .check(status.is(200))
      )
    }

  // C — authenticated users forging an impossible jump (Rennes → New York)
  def violation(tag: String) = scenario(s"ProximityViolation-$tag")
    .feed(Iterator.continually(Map("vid" -> (Random.nextInt(30) + 1))))
    .exec(
      http(s"$tag-login")
        .post("/auth/login")
        .body(StringBody(s"""{"username":"bizlogic-$tag-user#{vid}","device":"biz-device-#{vid}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
    .exitHereIfFailed
    .exec(
      http(s"$tag-anchor")
        .post("/location/update")
        .queryParam("lat", "48.11")
        .queryParam("lon", "-1.68")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200))
    )
    .exec(
      http(s"$tag-violation")
        .post("/location/update")
        .queryParam("lat", "40.71")
        .queryParam("lon", "-74.00")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200))
    )

  setUp(
    legitimate.inject(constantUsersPerSec(300).during(1500.seconds)),
    interception.inject(nothingFor(30.seconds), constantUsersPerSec(5).during(60.seconds)),
    violation("C1").inject(nothingFor(240.seconds), constantUsersPerSec(30).during(120.seconds)),
    // 30 attackers/s x 2 replays ≈ 10% of the ≈600 legitimate req/s
    replay.inject(nothingFor(540.seconds), constantUsersPerSec(30).during(180.seconds)),
    violation("C2").inject(nothingFor(840.seconds), constantUsersPerSec(30).during(120.seconds))
  ).protocols(httpProtocol)
}
