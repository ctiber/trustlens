package fr.irisa.trustlens.mslocation.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@Profile("v3")
public class RiskEvaluator {

    private static final String KEY_DEVICE   = "risk:device:%s";
    private static final String KEY_LAST_POS = "risk:lastpos:%s";

    @Value("${trustlens.risk-evaluator.impossible-travel-threshold-km:500}")
    private double impossibleTravelThresholdKm;

    @Value("${trustlens.risk-evaluator.device-fingerprint-enabled:true}")
    private boolean deviceFingerprintEnabled;

    private final StringRedisTemplate redis;

    public RiskEvaluator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public RiskDecision evaluateDevice(String userId, String currentDevice) {
        if (!deviceFingerprintEnabled) return RiskDecision.ALLOW;

        String key = String.format(KEY_DEVICE, userId);
        String knownDevice = redis.opsForValue().get(key);

        if (knownDevice == null) {
            redis.opsForValue().set(key, currentDevice != null ? currentDevice : "", Duration.ofHours(1));
            return RiskDecision.ALLOW;
        }
        if (currentDevice != null && !knownDevice.equals(currentDevice)) {
            log.warn("[RiskEvaluator] Device mismatch for user {} — known={} current={}",
                userId, knownDevice, currentDevice);
            return RiskDecision.deny("Device fingerprint mismatch");
        }
        return RiskDecision.ALLOW;
    }

    public RiskDecision evaluateLocation(String userId, double lat, double lon) {
        String key = String.format(KEY_LAST_POS, userId);
        String last = redis.opsForValue().get(key);

        String current = lat + "," + lon + "," + Instant.now().toEpochMilli();
        redis.opsForValue().set(key, current, Duration.ofHours(1));

        if (last == null) return RiskDecision.ALLOW;

        String[] parts = last.split(",");
        double lastLat  = Double.parseDouble(parts[0]);
        double lastLon  = Double.parseDouble(parts[1]);
        long   lastTime = Long.parseLong(parts[2]);

        double distanceKm = haversine(lastLat, lastLon, lat, lon);
        long   elapsedMs  = Instant.now().toEpochMilli() - lastTime;
        double speedKmH   = elapsedMs > 0
            ? distanceKm / (elapsedMs / 3_600_000.0)
            : Double.MAX_VALUE;

        if (speedKmH > 200 || distanceKm > impossibleTravelThresholdKm) {
            log.warn("[RiskEvaluator] Impossible travel for user {} — dist={}km speed={}km/h",
                userId, String.format("%.2f", distanceKm), String.format("%.2f", speedKmH));
            return RiskDecision.deny(
                String.format("Impossible travel: %.2f km in %dms", distanceKm, elapsedMs));
        }
        return RiskDecision.ALLOW;
    }

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

    public record RiskDecision(boolean allowed, String reason) {
        static final RiskDecision ALLOW = new RiskDecision(true, null);

        static RiskDecision deny(String reason) {
            return new RiskDecision(false, reason);
        }
    }
}
