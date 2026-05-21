package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Low-load simulation — models a quiet/night period.
 * Produces low T_avg and low U_res observations for history collection.
 * Expected context: R_low_benign
 */
class LowLoadSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val feeder = Iterator.continually {
    val id = Random.nextInt(100) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + Random.nextDouble() * 0.3),
      "lon"      -> (-2.0 + Random.nextDouble() * 0.3)
    )
  }

  val userScenario = scenario("LowLoad")
    .feed(feeder)
    .exec(
      http("login")
        .post("/auth/login")
        .body(StringBody("""{"username":"#{userId}","device":"#{deviceId}"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.token").saveAs("token"))
    )
    .exitHereIfFailed
    .pause(1.second)
    .exec(
      http("updateLocation")
        .post("/location/update")
        .queryParam("lat", "#{lat}")
        .queryParam("lon", "#{lon}")
        .header("Authorization", "Bearer #{token}")
        .check(status.in(200, 401, 403))
    )

  setUp(
    // Constant 50 RPS for 10 minutes — mimics quiet night traffic
    userScenario.inject(
      constantUsersPerSec(50).during(600.seconds)
    )
  ).protocols(httpProtocol)
}
