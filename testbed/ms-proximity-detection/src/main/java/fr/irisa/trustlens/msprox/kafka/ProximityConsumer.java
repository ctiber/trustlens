package fr.irisa.trustlens.msprox.kafka;

import fr.irisa.trustlens.msprox.service.ProximityService;
import fr.irisa.trustlens.zt.KafkaAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes user.location.updated events from Kafka and delegates
 * proximity rule evaluation to ProximityService.
 *
 * With p2 (ZT_AC4A) the consumer does not trust the upstream producer: it
 * verifies the JWT propagated in the record header and drops the event if it
 * is missing or invalid (access control on all services, including the
 * asynchronous tier).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProximityConsumer {

    private final ProximityService proximityService;
    private final KafkaAuth        kafkaAuth;

    @KafkaListener(
        topics    = "${kafka.topic.location:user.location.updated}",
        groupId   = "proximity-detection-group"
    )
    public void onLocationEvent(
            @Payload Map<String, Object> event,
            @Header(name = KafkaAuth.HEADER, required = false) byte[] token) {
        if (!kafkaAuth.accept(token)) return;
        log.debug("Received location event: {}", event);
        String userId = (String) event.get("userId");
        double lat    = ((Number) event.get("lat")).doubleValue();
        double lon    = ((Number) event.get("lon")).doubleValue();
        proximityService.evaluate(userId, lat, lon, token);
    }
}
