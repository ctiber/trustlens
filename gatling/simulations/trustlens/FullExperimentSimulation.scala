package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Full experiment simulation combining all three adversarial scenarios
 * within a single stepped load profile. Used by 06_baselines.sh to collect
 * comparison metrics across Static-V1, Static-V2, and Static-V3 baselines.
 *
 * Load profile (10 steps × 60s = 600s total):
 *   Step 1–2  (100–200 RPS, t=0–120s):   Scenario C injected at 10% (~15 RPS)
 *   Step 3    (300 RPS, t=120–180s):       baseline only
 *   Step 4–6  (400–600 RPS, t=180–360s):  Scenario A injected at 10% (~50 RPS)
 *   Step 7–10 (700–1000 RPS, t=360–600s): Scenario B injected at 10% (~85 RPS)
 *
 * Paper §IV-E.2: scenarios injected sequentially, each as a 10% malicious
 * mix within the regular Gatling load stream.
 */
class FullExperimentSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  // ── Feeders ───────────────────────────────────────────────────────────────

  val legitimateFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + Random.nextDouble() * 0.4),
      "lon"      -> (-2.0 + Random.nextDouble() * 0.4)
    )
  }

  val lateralFeeder = Iterator.continually {
    Map(
      "victimUserId" -> s"user${Random.nextInt(200) + 1}",
      "lat"          -> (48.0 + Random.nextDouble() * 0.4),
      "lon"          -> (-2.0 + Random.nextDouble() * 0.4)
    )
  }

  val replayFeeder = Iterator.continually {
    val vid = Random.nextInt(50) + 1
    Map(
      "victimUserId" -> s"user$vid",
      "preRotDevice" -> s"pre-rotation-device-$vid",
      "lat"          -> (48.0 + Random.nextDouble() * 0.4),
      "lon"          -> (-2.0 + Random.nextDouble() * 0.4)
    )
  }

  val violationFeeder = Iterator.continually {
    val id = Random.nextInt(30) + 1
    Map(
      "userId"   -> s"bizlogic-user$id",
      "deviceId" -> s"biz-device-$id"
    )
  }

  // ── Scenarios ─────────────────────────────────────────────────────────────

  val legitimate = scenario("LegitimateUser")
    .feed(legitimateFeeder)
    .exec(
      http("login")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
    .exec(
      http("updateLocation")
        .post("/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 401, 403))
    )

  // Scenario A: bypass gateway, inject during medium-load phase
  val lateralMovement = scenario("LateralMovement")
    .feed(lateralFeeder)
    .exec(
      http("bypass-gateway-direct-location")
        .post("http://localhost:8082/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        .header("X-Forged-UserId", "#{victimUserId}")
        .check(status.in(200, 401, 403, 503))
    )

  // Scenario B: credential replay, inject during high-load phase
  val credentialReplay = scenario("CredentialReplay")
    .feed(replayFeeder)
    .exec(
      http("capture-token")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{victimUserId}","device":"#{preRotDevice}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("capturedToken"))
    )
    .repeat(5) {
      exec(
        http("replay-post-rotation")
          .post("/location/update")
          .queryParam("lat", "#{lat}")
          .queryParam("lon", "#{lon}")
          .header("Authorization", "Bearer #{capturedToken}")
          .check(status.in(200, 401, 403))
      )
    }

  // Scenario C: proximity violation, inject during low-load phase
  val proximityViolation = scenario("ProximityViolation")
    .feed(violationFeeder)
    .exec(
      http("login")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
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

  // ── Injection plan ────────────────────────────────────────────────────────

  setUp(
    // Background: full stepped load (10 steps × 60s)
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
    // Scenario C: low-load phase t=0–120s, 10% of ~150 RPS = 15 RPS
    proximityViolation.inject(
      constantUsersPerSec(15).during(120.seconds)
    ),
    // Scenario A: medium-load phase t=180–360s, 10% of ~500 RPS = 50 RPS
    lateralMovement.inject(
      nothingFor(180.seconds),
      constantUsersPerSec(50).during(180.seconds)
    ),
    // Scenario B: high-load phase t=360–600s, 10% of ~850 RPS = 85 RPS
    credentialReplay.inject(
      nothingFor(360.seconds),
      constantUsersPerSec(85).during(240.seconds)
    )
  ).protocols(httpProtocol)
}
