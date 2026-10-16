package fr.irisa.trustlens.mslocation.kafka;

import fr.irisa.trustlens.zt.KafkaAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Publishes location events to the user.location.updated Kafka topic.
 *
 * With p2 (ZT_AC4A) the caller's JWT is propagated as a record header so that
 * downstream consumers can verify it. With p3 (ZT_MTLS) the producer connects
 * to the broker's SSL listener with its client certificate; the plaintext
 * listener is closed, so an unauthenticated internal producer (Scenario A)
 * cannot reach the topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationProducer {

    @Value("${kafka.topic.location:user.location.updated}")
    private String topic;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaAuth                     kafkaAuth;

    public void publish(String userId, Map<String, Object> event, String token) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, userId, event);
        if (kafkaAuth.enabled() && token != null) {
            record.headers().add(KafkaAuth.HEADER, token.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish location event for {}: {}",
                        userId, ex.getMessage());
                }
            });
    }
}
