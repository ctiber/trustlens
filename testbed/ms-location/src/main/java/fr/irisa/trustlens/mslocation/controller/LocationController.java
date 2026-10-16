package fr.irisa.trustlens.mslocation.controller;

import fr.irisa.trustlens.mslocation.kafka.LocationProducer;
import fr.irisa.trustlens.zt.JwtService;
import fr.irisa.trustlens.zt.ZtRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives GPS coordinates from clients and publishes them to the
 * user.location.updated Kafka topic.
 *
 * Zero Trust checks (JWT = p2, risk analysis = p5) are applied upstream by the
 * zt-common servlet filters according to the active ZT_* flags; this
 * controller only sees requests that passed them.
 *
 * The last known position of each user is cached in Redis (read by the
 * proximity chain). This write is on the request path in every variant, so a
 * Redis outage degrades the service (5xx) independently of the security
 * posture — the "degraded" runtime context of Phase 1.
 */
@RestController
@RequestMapping("/location")
@RequiredArgsConstructor
public class LocationController {

    private static final String LAST_POS_KEY = "location:last:%s";

    private final LocationProducer    producer;
    private final StringRedisTemplate redis;

    /**
     * POST /location/update?lat=48.11&lon=-1.68
     */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> updateLocation(
            HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam double lat,
            @RequestParam double lon) {

        String userId = ZtRequest.userId(request);
        String resolvedUserId = userId != null ? userId : "anonymous";
        long   now = System.currentTimeMillis();

        redis.opsForValue().set(String.format(LAST_POS_KEY, resolvedUserId),
            lat + "," + lon + "," + now);

        Map<String, Object> event = Map.of(
            "userId",    resolvedUserId,
            "lat",       lat,
            "lon",       lon,
            "timestamp", now
        );
        producer.publish(resolvedUserId, event, JwtService.bearer(authorization));

        return ResponseEntity.ok(Map.of(
            "status",  "published",
            "userId",  resolvedUserId,
            "lat",     lat,
            "lon",     lon
        ));
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ms-location OK");
    }
}
