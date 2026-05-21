package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Mixed-attack simulation — 90% legitimate traffic + 10% adversarial.
 * Used during Phase 1 history collection to produce observations with
 * non-zero R_sec, enabling DBSCAN to identify an adversarial context
 * from intentional data rather than incidental V2 noise.
 *
 * Three attack types are interleaved:
 *   A. Unsigned requests (missing Authorization header) → 401
 *   B. Expired/invalid JWT → 401/403
 *   C. Impossible travel (proximity violation) → 403 in V3
 *
 * Expected feature signal: R_sec ≈ 0.05–0.15, T_avg moderate.
 */
class MixedAttackSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  // ── Legitimate users ───────────────────────────────────────────────────────
  val legitimateFeeder = Iterator.continually {
    val id = Random.nextInt(300) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      // Fixed position per user ID — avoids impossible-travel false positives in V3
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  val legitimate = scenario("Legitimate")
    .feed(legitimateFeeder)
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
        .check(status.in(200, 401, 403))
    )

  // ── Attack A: unsigned request (no Authorization header) → 401 ─────────────
  val unsignedFeeder = Iterator.continually(
    Map("lat" -> (48.0 + Random.nextDouble() * 0.3),
        "lon" -> (-2.0 + Random.nextDouble() * 0.3))
  )

  val unsignedAttack = scenario("UnsignedRequest")
    .feed(unsignedFeeder)
    .exec(
      http("unsigned-location")
        .post("/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        // No Authorization header — will be rejected with 401
        .check(status.in(200, 401, 403))
    )

  // ── Attack B: invalid JWT (corrupted token) → 401/403 ─────────────────────
  val invalidJwtAttack = scenario("InvalidJWT")
    .exec(
      http("invalid-token")
        .post("/location/update")
        .queryParam("lat", "48.11")
        .queryParam("lon", "-1.68")
        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.invalid.signature")
        .check(status.in(200, 401, 403))
    )

  // ── Attack C: impossible travel (proximity violation) → 403 in V3 ──────────
  val travelFeeder = Iterator.continually {
    val id = Random.nextInt(50) + 1
    Map("userId" -> s"travel-user$id", "deviceId" -> s"travel-device-$id")
  }

  val impossibleTravel = scenario("ImpossibleTravel")
    .feed(travelFeeder)
    .exec(
      http("login-travel")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
    .exitHereIfFailed
    .exec(
      http("anchor-rennes")
        .post("/location/update")
        .queryParam("lat", "48.11")
        .queryParam("lon", "-1.68")
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 401, 403))
    )
    .exec(
      http("jump-new-york")
        .post("/location/update")
        .queryParam("lat", "40.71")
        .queryParam("lon", "-74.00")
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 401, 403))
    )

  setUp(
    // 90% legitimate at 300 RPS for 10 minutes
    legitimate.inject(
      constantUsersPerSec(270).during(600.seconds)
    ),
    // 10% attack mix — three attack types share the 30 RPS budget
    unsignedAttack.inject(
      constantUsersPerSec(10).during(600.seconds)
    ),
    invalidJwtAttack.inject(
      constantUsersPerSec(10).during(600.seconds)
    ),
    impossibleTravel.inject(
      constantUsersPerSec(10).during(600.seconds)
    )
  ).protocols(httpProtocol)
}
