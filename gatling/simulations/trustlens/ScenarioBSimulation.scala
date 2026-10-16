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
 * Simulation: regular users' tokens are intercepted during the first minute
 * (pre-rotation) and replayed during the high-load phase, after Vault has
 * rotated the signing key several times.
 *
 * Injection protocol (paper §IV-E.2):
 *   - 10% malicious mix within the regular stepped load stream
 *   - Injected during the HIGH-LOAD phase (≥700 RPS, t=360s–600s)
 *   - Background: full 10-step profile (100→1000 RPS, 60s/step)
 */
/** Tokens intercepted early in the run, shared between scenarios. */
object CapturedTokens {
  private val tokens = new java.util.concurrent.CopyOnWriteArrayList[(String, Double, Double)]()
  def store(t: String, lat: Double, lon: Double): Unit = tokens.add((t, lat, lon))
  def pick(): (String, Double, Double) =
    if (tokens.isEmpty) ("none", 48.0, -2.0) else tokens.get(Random.nextInt(tokens.size))
}

class ScenarioBSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")
    // Virtual users share a keep-alive connection pool (API clients behind a
    // gateway); avoids connection-churn resets in the Docker port proxy.
    .shareConnections

  val legitimateFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  // Victims are regular users: the attacker intercepts THEIR tokens (same
  // device claim, same position), so the replay is only distinguishable by
  // the age of the token, i.e. by the key version it was signed with (p4).
  val victimFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "victimUserId" -> s"user$id",
      "victimDevice" -> s"device-$id",
      "lat"          -> (48.0 + (id % 30) * 0.01),
      "lon"          -> (-2.0 + (id % 30) * 0.01)
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

  // Interception: during the first minute, sessions of regular users are
  // eavesdropped and their JWTs stored (pre-rotation tokens).
  val interception = scenario("TokenInterception")
    .feed(victimFeeder)
    .exec(
      http("intercepted-session")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{victimUserId}","device":"#{victimDevice}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("stolen"))
    )
    .exec { session =>
      CapturedTokens.store(session("stolen").as[String], session("lat").as[Double], session("lon").as[Double])
      session
    }

  // Replay: during the high-load phase the stolen tokens are replayed.
  //   - With p4 (Vault rotation every 60 s, one version of grace) the tokens,
  //     now > 5 min old, carry a superseded kid -> 401 -> R_sec rises.
  //   - Without p4 (static secret) they are accepted -> 200, undetected.
  val replayAttack = scenario("CredentialReplay")
    .exec { session =>
      val (token, lat, lon) = CapturedTokens.pick()
      session.set("capturedToken", token).set("lat", lat).set("lon", lon)
    }
    .repeat(2) {
      exec(
        http("replay-captured-token")
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
    // Token interception during the low-load phase (t=0s to t=60s)
    interception.inject(constantUsersPerSec(5).during(60.seconds)),
    // 10% malicious mix during high-load phase (t=360s to t=600s):
    // 85 attackers/s x 2 replays ≈ 10% of the ≈1700 legitimate req/s
    replayAttack.inject(
      nothingFor(360.seconds),
      constantUsersPerSec(85).during(240.seconds)
    )
  ).protocols(httpProtocol)
}
