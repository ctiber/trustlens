package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Peak-load simulation — models a sustained high-traffic period.
 * Produces high T_avg and high U_res observations for history collection.
 * Expected context: R_peak_benign
 */
class PeakLoadSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val feeder = Iterator.continually {
    val id = Random.nextInt(500) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      // Fixed position per user ID — avoids impossible-travel false positives in V3
      "lat"      -> (48.0 + (id % 30) * 0.01),
      "lon"      -> (-2.0 + (id % 30) * 0.01)
    )
  }

  val userScenario = scenario("PeakLoad")
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
        .check(status.in(200, 401, 403))
    )

  setUp(
    // Ramp up to 800 RPS then sustain — mimics peak hour traffic
    userScenario.inject(
      rampUsersPerSec(100).to(800).during(120.seconds),
      constantUsersPerSec(800).during(480.seconds)
    )
  ).protocols(httpProtocol)
}
