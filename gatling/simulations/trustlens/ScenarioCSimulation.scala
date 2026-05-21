package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Scenario C — Business Logic Violation (paper §IV-E.1.c).
 *
 * A proximity rule violation is injected via MS-Location during a LOW-LOAD
 * quiescent period, when TrustLens may have recommended a lightweight
 * configuration. Simulated by posting consecutive location updates that
 * violate geofence rules stored in Redis (out-of-bounds coordinates), or
 * that imply physically impossible travel speed.
 *
 * This probes whether Layer 2 detects the resulting R_sec anomaly quickly
 * enough to alert the operator BEFORE the violation propagates through the
 * Kafka pipeline to MS-ProximityDetection and MS-Notification.
 * Expected feature response: R_sec anomaly (rise in 4xx ratio).
 *
 * Injection protocol (paper §IV-E.2):
 *   - 10% malicious mix within the regular stepped load stream
 *   - Injected during the LOW-LOAD phase (≤200 RPS, t=0s–120s)
 *   - Background: full 10-step profile (100→1000 RPS, 60s/step)
 */
class ScenarioCSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val legitimateFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  val violationFeeder = Iterator.continually {
    val id = Random.nextInt(30) + 1
    Map(
      "userId"   -> s"bizlogic-user$id",
      "deviceId" -> s"biz-device-$id"
    )
  }

  val legitimate = scenario("LegitimateUser")
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

  // Violation attack:
  //   1. Login and anchor near Rennes (within expected geofence)
  //   2. Immediately post from New York (5700 km — impossible travel)
  //      In V3, RiskEvaluatorFilter detects impossible speed → 403
  //      In V1/V2, no stateful check → location accepted, violation propagates to Kafka
  val proximityViolation = scenario("ProximityViolation")
    .feed(violationFeeder)
    .exec(
      http("login")
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
      http("violation-new-york")
        .post("/location/update")
        .queryParam("lat", "40.71")
        .queryParam("lon", "-74.00")
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 401, 403))
    )

  setUp(
    // Full stepped load: 10 steps × 60s = 600s
    legitimate.inject(
      constantUsersPerSec(100).during(60.seconds),
      constantUsersPerSec(200).during(60.seconds),
      constantUsersPerSec(300).during(60.seconds),
      constantUsersPerSec(400).during(60.seconds),
      constantUsersPerSec(500).during(60.seconds),
      constantUsersPerSec(600).during(60.seconds),
      constantUsersPerSec(700).during(60.seconds),
      constantUsersPerSec(800).during(60.seconds),
      constantUsersPerSec(900).during(60.seconds),
      constantUsersPerSec(1000).during(60.seconds)
    ),
    // 10% malicious mix during low-load phase (t=0s to t=120s, avg ~150 RPS → 15 attack RPS)
    proximityViolation.inject(
      constantUsersPerSec(15).during(120.seconds)
    )
  ).protocols(httpProtocol)
}
