package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Scenario A — Lateral Movement and Data Forgery (paper §IV-E.1.a).
 *
 * A compromised internal service bypasses the API Gateway and publishes
 * forged GPS coordinates DIRECTLY to MS-Location at port 8082, skipping
 * the perimeter control (p1). In V1/V2 this succeeds; in V3, mTLS (p3)
 * and the RiskEvaluator (p5) block unauthenticated internal connections.
 * Expected feature response: rise in R_sec, stable T_avg and U_res.
 *
 * Injection protocol (paper §IV-E.2):
 *   - 10% malicious mix within the regular stepped load stream
 *   - Injected during the MEDIUM-LOAD phase (400–600 RPS, t=180s–360s)
 *   - Background: full 10-step profile (100→1000 RPS, 60s/step)
 */
class ScenarioASimulation extends Simulation {

  // Legitimate traffic goes through the API Gateway (perimeter control active)
  val gatewayProtocol = http
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

  val attackFeeder = Iterator.continually {
    val id = Random.nextInt(50) + 1
    Map(
      "victimUserId" -> s"user${Random.nextInt(200) + 1}",
      "lat"          -> (48.0 + Random.nextDouble() * 0.4),
      "lon"          -> (-2.0 + Random.nextDouble() * 0.4)
    )
  }

  // Normal users: login via gateway, then post location via gateway
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

  // Attacker: bypass gateway, POST forged coordinates directly to ms-location:8082
  // Absolute URL overrides baseUrl — simulates compromised internal service
  val attacker = scenario("LateralMovement")
    .feed(attackFeeder)
    .exec(
      http("bypass-gateway-direct-location")
        .post("http://localhost:8082/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        .header("X-Forged-UserId", "#{victimUserId}")
        .check(status.in(200, 401, 403, 503))
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
    // 10% malicious mix during medium-load phase (t=180s to t=360s, avg ~500 RPS → 50 attack RPS)
    attacker.inject(
      nothingFor(180.seconds),
      constantUsersPerSec(50).during(180.seconds)
    )
  ).protocols(gatewayProtocol)
}
