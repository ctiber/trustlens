package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Bursty-load simulation — alternates between high and low load.
 * Produces high variance in T_avg and U_res, capturing the transition
 * behaviour that a stepped profile misses.
 * Expected context: distinct from both R_low and R_peak due to variance.
 */
class BurstyLoadSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val feeder = Iterator.continually {
    val id = Random.nextInt(300) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + Random.nextDouble() * 0.3),
      "lon"      -> (-2.0 + Random.nextDouble() * 0.3)
    )
  }

  val userScenario = scenario("BurstyLoad")
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
    // 3 burst cycles: spike to 700 RPS then drop to 80 RPS
    userScenario.inject(
      // Burst 1
      rampUsersPerSec(80).to(700).during(30.seconds),
      constantUsersPerSec(700).during(60.seconds),
      rampUsersPerSec(700).to(80).during(30.seconds),
      constantUsersPerSec(80).during(60.seconds),
      // Burst 2
      rampUsersPerSec(80).to(700).during(30.seconds),
      constantUsersPerSec(700).during(60.seconds),
      rampUsersPerSec(700).to(80).during(30.seconds),
      constantUsersPerSec(80).during(60.seconds),
      // Burst 3
      rampUsersPerSec(80).to(700).during(30.seconds),
      constantUsersPerSec(700).during(60.seconds),
      rampUsersPerSec(700).to(80).during(30.seconds),
      constantUsersPerSec(80).during(60.seconds)
    )
  ).protocols(httpProtocol)
}
