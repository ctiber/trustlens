package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Degraded-service simulation — moderate load combined with a Redis
 * fault injection window to produce non-zero F_rate observations.
 *
 * Redis is paused externally during the fault window using:
 *   docker pause trustlens_redis_1
 *   (wait 120s)
 *   docker unpause trustlens_redis_1
 *
 * When Redis is unavailable, proximity rule lookups fail with HTTP 500,
 * producing F_rate > 0 in Prometheus metrics.
 *
 * This simulation is run from the 04_phase1_profiling_rich.sh script,
 * which handles the docker pause/unpause timing automatically.
 *
 * Expected feature signal:
 *   - Pre-fault  : T_avg moderate, F_rate ≈ 0, R_sec ≈ 0
 *   - Fault window: T_avg rises (timeouts), F_rate > 0, R_sec ≈ 0
 *   - Post-fault : returns to baseline
 */
class DegradedSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val feeder = Iterator.continually {
    val id = Random.nextInt(200) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      // Fixed position per user ID — avoids impossible-travel false positives in V3
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  val userScenario = scenario("DegradedLoad")
    .feed(feeder)
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
        // Accept 500 during fault window — do not abort on 5xx
        .check(status.in(200, 401, 403, 500, 503))
    )

  setUp(
    // Constant 300 RPS for 10 minutes
    // Redis fault is injected externally between t=120s and t=240s
    userScenario.inject(
      constantUsersPerSec(300).during(600.seconds)
    )
  ).protocols(httpProtocol)
}
