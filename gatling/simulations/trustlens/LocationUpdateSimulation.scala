package trustlens

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Constant 500 RPS load for 120 seconds.
 * Used by 03_isolate_primitives.sh to measure per-primitive overhead.
 * Each virtual user: login → location update.
 */
class LocationUpdateSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")

  val userFeeder = Iterator.continually {
    val id = Random.nextInt(100) + 1
    Map(
      "userId"   -> s"user$id",
      "deviceId" -> s"device-$id",
      "lat"      -> (48.0 + Random.nextDouble() * 0.5),
      "lon"      -> (-2.0 + Random.nextDouble() * 0.5)
    )
  }

  val scn = scenario("LocationUpdate")
    .feed(userFeeder)
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
    scn.inject(constantUsersPerSec(500).during(120.seconds))
  ).protocols(httpProtocol)
}
