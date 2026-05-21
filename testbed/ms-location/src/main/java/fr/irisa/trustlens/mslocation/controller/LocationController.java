package fr.irisa.trustlens.mslocation.controller;

import fr.irisa.trustlens.mslocation.kafka.LocationProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives GPS coordinates from authenticated clients and publishes
 * them to the user.location.updated Kafka topic.
 *
 * In V3, the RiskEvaluatorFilter (ms-user) has already vetted the
 * request before it reaches this controller via the API Gateway.
 * This service adds a second PEP layer by validating the JWT locally
 * (V2/V3) and optionally checking proximity rules against Redis (V3).
 */
@RestController
@RequestMapping("/location")
@RequiredArgsConstructor
public class LocationController {

    private final LocationProducer producer;

    /**
     * POST /location/update
     * Body: { "lat": 48.11, "lon": -1.68 }
     *
     * The lat/lon parameters are also passed as query params for the
     * RiskEvaluatorFilter (which reads them from the request URI).
     */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> updateLocation(
            @AuthenticationPrincipal String userId,
            @RequestParam double lat,
            @RequestParam double lon) {

        String resolvedUserId = userId != null ? userId : "anonymous";
        Map<String, Object> event = Map.of(
            "userId",    resolvedUserId,
            "lat",       lat,
            "lon",       lon,
            "timestamp", System.currentTimeMillis()
        );

        producer.publish(resolvedUserId, event);

        return ResponseEntity.ok(Map.of(
            "status",  "published",
            "userId",  resolvedUserId,
            "lat",     lat,
            "lon",     lon
        ));
    }

    /** Health / smoke-test endpoint */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ms-location OK");
    }
}
