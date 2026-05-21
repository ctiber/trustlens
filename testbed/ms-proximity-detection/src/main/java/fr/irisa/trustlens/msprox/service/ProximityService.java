package fr.irisa.trustlens.msprox.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Evaluates proximity constraints stored in Redis.
 *
 * Rules are stored as Redis hashes under the key "proximity:rules:{userId}",
 * with fields:
 *   max_lat, min_lat, max_lon, min_lon  (geofence bounding box)
 *
 * If the reported position violates the constraint, a violation event
 * is published to the proximity.violation.detected Kafka topic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProximityService {

    private static final String RULES_KEY         = "proximity:rules:%s";
    private static final String VIOLATION_TOPIC   = "proximity.violation.detected";

    private final StringRedisTemplate            redis;
    private final KafkaTemplate<String, Object>  kafka;

    public void evaluate(String userId, double lat, double lon) {
        String key = String.format(RULES_KEY, userId);

        String maxLatStr = redis.opsForHash().get(key, "max_lat") != null
            ? (String) redis.opsForHash().get(key, "max_lat") : null;

        if (maxLatStr == null) {
            log.debug("No proximity rules for user {}", userId);
            return;
        }

        double maxLat = Double.parseDouble(maxLatStr);
        double minLat = Double.parseDouble(
            (String) redis.opsForHash().get(key, "min_lat"));
        double maxLon = Double.parseDouble(
            (String) redis.opsForHash().get(key, "max_lon"));
        double minLon = Double.parseDouble(
            (String) redis.opsForHash().get(key, "min_lon"));

        boolean violation = lat > maxLat || lat < minLat
                         || lon > maxLon || lon < minLon;

        if (violation) {
            log.warn("[ProximityService] Violation for user {} at ({}, {})",
                userId, lat, lon);
            kafka.send(VIOLATION_TOPIC, userId, java.util.Map.of(
                "userId",    userId,
                "lat",       lat,
                "lon",       lon,
                "timestamp", System.currentTimeMillis(),
                "reason",    "Geofence breach"
            ));
        } else {
            log.debug("[ProximityService] OK for user {} at ({}, {})",
                userId, lat, lon);
        }
    }

    /** Seed a geofence rule for a user (used by test setup / admin). */
    public void setRule(String userId,
                        double minLat, double maxLat,
                        double minLon, double maxLon) {
        String key = String.format(RULES_KEY, userId);
        redis.opsForHash().put(key, "min_lat", String.valueOf(minLat));
        redis.opsForHash().put(key, "max_lat", String.valueOf(maxLat));
        redis.opsForHash().put(key, "min_lon", String.valueOf(minLon));
        redis.opsForHash().put(key, "max_lon", String.valueOf(maxLon));
    }
}
