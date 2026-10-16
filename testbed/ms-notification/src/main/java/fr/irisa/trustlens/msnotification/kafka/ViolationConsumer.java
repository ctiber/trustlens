package fr.irisa.trustlens.msnotification.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import fr.irisa.trustlens.zt.KafkaAuth;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Downstream consumer of the proximity.violation.detected topic.
 * Stores violation records in Redis for operator review and exposes
 * them via the actuator/prometheus endpoint for metric collection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViolationConsumer {

    private static final String VIOLATION_KEY = "notifications:violations:%s:%d";

    private final StringRedisTemplate redis;
    private final KafkaAuth           kafkaAuth;

    @KafkaListener(
        topics  = "${kafka.topic.violations:proximity.violation.detected}",
        groupId = "notification-group"
    )
    public void onViolation(
            @Payload Map<String, Object> event,
            @Header(name = KafkaAuth.HEADER, required = false) byte[] token) {
        if (!kafkaAuth.accept(token)) return;
        String userId    = (String) event.get("userId");
        String reason    = (String) event.getOrDefault("reason", "unknown");
        long   timestamp = event.get("timestamp") != null
            ? ((Number) event.get("timestamp")).longValue()
            : Instant.now().toEpochMilli();

        log.warn("[Notification] Proximity violation — userId={} reason={} ts={}",
            userId, reason, timestamp);

        String key = String.format(VIOLATION_KEY, userId, timestamp);
        redis.opsForHash().put(key, "reason", reason);
        redis.opsForHash().put(key, "timestamp", String.valueOf(timestamp));
    }
}
