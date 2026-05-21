package fr.irisa.trustlens.mslocation.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes location events to the user.location.updated Kafka topic.
 *
 * In Scenario A (Lateral Movement), an attacker tries to publish directly
 * to this topic, bypassing the API Gateway and this producer.
 * The mTLS requirement (V3) prevents unauthenticated producers from
 * connecting to the Kafka broker.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationProducer {

    @Value("${kafka.topic.location:user.location.updated}")
    private String topic;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String userId, Map<String, Object> event) {
        kafkaTemplate.send(topic, userId, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish location event for {}: {}",
                        userId, ex.getMessage());
                } else {
                    log.debug("Location event published for {} offset={}",
                        userId,
                        result.getRecordMetadata().offset());
                }
            });
    }
}
