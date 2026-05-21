package fr.irisa.trustlens.msprox.kafka;

import fr.irisa.trustlens.msprox.service.ProximityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes user.location.updated events from Kafka and delegates
 * proximity rule evaluation to ProximityService.
 *
 * This service is stateless with respect to security; it relies on
 * the upstream services (ms-user, ms-location) to have enforced the
 * active trust strategy before the event reaches the Kafka topic.
 *
 * Scenario C (Business Logic Attack): in V1/V2, a forged event can
 * reach this consumer because the producer-side PEP does not block it.
 * In V3, the RiskEvaluatorFilter intercepts the forged request before
 * it is published, so this consumer never sees it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProximityConsumer {

    private final ProximityService proximityService;

    @KafkaListener(
        topics    = "${kafka.topic.location:user.location.updated}",
        groupId   = "proximity-detection-group"
    )
    public void onLocationEvent(Map<String, Object> event) {
        log.debug("Received location event: {}", event);
        String userId = (String) event.get("userId");
        double lat    = ((Number) event.get("lat")).doubleValue();
        double lon    = ((Number) event.get("lon")).doubleValue();
        proximityService.evaluate(userId, lat, lon);
    }
}
