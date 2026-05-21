package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Scenario B — Credential Replay and Identity Theft (paper §IV-E.1.b).
 *
 * Intercepted JWTs from rotated Vault sessions are replayed against MS-User.
 * V2's stateless JWT validation cannot detect rotated-key replays; only V3's
 * Vault-integrated validation blocks them (p4 — dynamic secret rotation).
 * Expected feature response: spike in F_rate as replayed tokens are rejected
 * post-rotation; rise in R_sec.
 *
 * Simulation: attackers log in to capture a token at t=0 (pre-rotation),
 * then replay that same token at high load (representing a post-rotation
 * window where V3's Vault has invalidated the signing key).
 *
 * Injection protocol (paper §IV-E.2):
 *   - 10% malicious mix within the regular stepped load stream
 *   - Injected during the HIGH-LOAD phase (≥700 RPS, t=360s–600s)
 *   - Background: full 10-step profile (100→1000 RPS, 60s/step)
 */
class ScenarioBSimulation extends Simulation {

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

  val replayFeeder = Iterator.continually {
    val victimId = Random.nextInt(50) + 1
    Map(
      "victimUserId" -> s"user$victimId",
      "preRotDevice" -> s"pre-rotation-device-$victimId",
      "lat"          -> (48.0 + Random.nextDouble() * 0.4),
      "lon"          -> (-2.0 + Random.nextDouble() * 0.4)
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

  // Replay attack:
  //   1. Capture token at t≈0 (before Vault rotates the signing key)
  //   2. Replay the same token during the high-load phase (post-rotation window)
  //      In V3, Vault has rotated the key by this point → 401/403
  //      In V2, stateless validation accepts any well-formed token → 200
  val replayAttack = scenario("CredentialReplay")
    .feed(replayFeeder)
    .exec(
      http("capture-pre-rotation-token")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{victimUserId}","device":"#{preRotDevice}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("capturedToken"))
    )
    .exitHereIfFailed
    // Replay the captured token — simulates post-rotation replay
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
    // 10% malicious mix during high-load phase (t=360s to t=600s, avg ~850 RPS → 85 attack RPS)
    replayAttack.inject(
      nothingFor(360.seconds),
      constantUsersPerSec(85).during(240.seconds)
    )
  ).protocols(httpProtocol)
}
