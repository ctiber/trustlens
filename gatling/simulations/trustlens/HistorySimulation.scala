package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * History simulation for Layer 1 profiling (04_phase1_profiling.sh).
 *
 * Combines the stepped load (100→1000 RPS over 600s) with a parallel
 * stream of credential-stuffing attacks at 10% of the legitimate rate.
 * The attack requests carry a hard-coded invalid JWT token:
 *   - V2 / V2-mtls / V2-vault / V3: JWT filter rejects them → 401 → R_sec > 0
 *   - V1: no JWT filter → endpoint returns 200 → R_sec ≈ 0 (expected:
 *     V1 lacks the defence to detect credential-stuffing)
 *
 * Paper §IV-D: the historical dataset D must span both benign and
 * under-attack conditions so that Φ(s, R_i) scores yield non-trivial
 * security recommendations for attack-adjacent regimes.
 */
class HistorySimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val legitimateFeeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + Random.nextDouble() * 0.5),
      "lon"      -> (-2.0 + Random.nextDouble() * 0.5)
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

  // Credential-stuffing: a fixed invalid token ensures we always hit
  // the JWT validation path (not a parse error before reaching Spring).
  val attacker = scenario("CredentialStuffing")
    .exec(
      http("invalid-jwt")
        .post("/location/update")
        .queryParam("lat", "48.1")
        .queryParam("lon", "-2.1")
        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhdHRhY2tlciJ9.INVALID_SIGNATURE")
        .check(status.in(200, 401, 403, 404))
    )

  setUp(
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
    // 10% attack mix, scaled with load so R_sec stays ≈ constant ≈ 0.09
    attacker.inject(
      constantUsersPerSec(10).during(60.seconds),
      constantUsersPerSec(20).during(60.seconds),
      constantUsersPerSec(30).during(60.seconds),
      constantUsersPerSec(40).during(60.seconds),
      constantUsersPerSec(50).during(60.seconds),
      constantUsersPerSec(60).during(60.seconds),
      constantUsersPerSec(70).during(60.seconds),
      constantUsersPerSec(80).during(60.seconds),
      constantUsersPerSec(90).during(60.seconds),
      constantUsersPerSec(100).during(60.seconds)
    )
  ).protocols(httpProtocol)
}
