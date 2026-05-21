package fr.irisa.trustlens.msuser.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * RiskEvaluator — primitive p5 (Continuous Risk Evaluation).
 *
 * Performs two stateful checks per authenticated request:
 *   1. Device fingerprint consistency: rejects tokens presented
 *      from a device different from the one used at login
 *      (defends against Scenario B — credential replay).
 *   2. Impossible travel detection: rejects location updates that
 *      would require physically impossible movement speed since the
 *      last known position (defends against Scenario A — data forgery).
 *
 * State is stored in Redis with a TTL matching the JWT expiration.
 */
@Slf4j
@Component
@Profile("v3")
public class RiskEvaluator {

    private static final String KEY_DEVICE   = "risk:device:%s";
    private static final String KEY_LAST_POS = "risk:lastpos:%s";

    @Value("${trustlens.risk-evaluator.impossible-travel-threshold-km:50}")
    private double impossibleTravelThresholdKm;

    @Value("${trustlens.risk-evaluator.device-fingerprint-enabled:true}")
    private boolean deviceFingerprintEnabled;

    private final StringRedisTemplate redis;

    public RiskEvaluator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Register device fingerprint on first authenticated request.
     * Subsequent requests from a different device are flagged.
     */
    public RiskDecision evaluateDevice(String userId, String currentDevice) {
        if (!deviceFingerprintEnabled) return RiskDecision.ALLOW;

        String key = String.format(KEY_DEVICE, userId);
        String knownDevice = redis.opsForValue().get(key);

        if (knownDevice == null) {
            redis.opsForValue().set(key, currentDevice, Duration.ofHours(1));
            return RiskDecision.ALLOW;
        }
        if (!knownDevice.equals(currentDevice)) {
            log.warn("[RiskEvaluator] Device mismatch for user {} — "
                + "known={} current={}", userId, knownDevice, currentDevice);
            return RiskDecision.deny("Device fingerprint mismatch");
        }
        return RiskDecision.ALLOW;
    }

    /**
     * Detect impossible travel: if the distance between the last known
     * position and the new position implies a speed above a threshold,
     * the update is rejected.
     *
     * @param userId   authenticated user
     * @param lat      new latitude
     * @param lon      new longitude
     */
    public RiskDecision evaluateLocation(
            String userId, double lat, double lon) {

        String key = String.format(KEY_LAST_POS, userId);
        String last = redis.opsForValue().get(key);

        String current = lat + "," + lon + "," + Instant.now().getEpochSecond();
        redis.opsForValue().set(key, current, Duration.ofHours(1));

        if (last == null) return RiskDecision.ALLOW;

        String[] parts = last.split(",");
        double lastLat  = Double.parseDouble(parts[0]);
        double lastLon  = Double.parseDouble(parts[1]);
        long   lastTime = Long.parseLong(parts[2]);

        double distanceKm = haversine(lastLat, lastLon, lat, lon);
        long   elapsedSec = Instant.now().getEpochSecond() - lastTime;
        double speedKmH   = elapsedSec > 0
            ? distanceKm / (elapsedSec / 3600.0)
            : Double.MAX_VALUE;

        // Threshold: 200 km/h ≈ fast train; anything above is suspicious
        if (speedKmH > 200 || distanceKm > impossibleTravelThresholdKm) {
            log.warn("[RiskEvaluator] Impossible travel for user {} — "
                + "dist={}km speed={}km/h", userId,
                String.format("%.2f", distanceKm),
                String.format("%.2f", speedKmH));
            return RiskDecision.deny(
                String.format("Impossible travel: %.2f km in %ds", distanceKm, elapsedSec)
            );
        }
        return RiskDecision.ALLOW;
    }

    // ── Haversine formula ─────────────────────────────────────────────────────

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
            * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ── Decision record ───────────────────────────────────────────────────────

    public record RiskDecision(boolean allowed, String reason) {
        static final RiskDecision ALLOW = new RiskDecision(true, null);

        static RiskDecision deny(String reason) {
            return new RiskDecision(false, reason);
        }
    }
}
