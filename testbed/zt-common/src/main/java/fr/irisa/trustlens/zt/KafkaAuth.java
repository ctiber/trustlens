package fr.irisa.trustlens.zt;

import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * p2 on the asynchronous path: with ZT_AC4A the producer propagates the
 * caller's JWT as a Kafka record header and every consumer verifies it before
 * processing the event ("access control on ALL services", incl. consumers).
 */
@Component
public class KafkaAuth {

    public static final String HEADER = "zt-auth";
    private static final Logger log = LoggerFactory.getLogger(KafkaAuth.class);

    private final ZtProperties props;
    private final JwtService   jwt;
    private final Counter      accepted;
    private final Counter      rejected;

    public KafkaAuth(ZtProperties props, JwtService jwt, MeterRegistry registry) {
        this.props = props;
        this.jwt   = jwt;
        this.accepted = Counter.builder("zt.ac4a.kafka").tag("result", "accepted").register(registry);
        this.rejected = Counter.builder("zt.ac4a.kafka").tag("result", "rejected").register(registry);
    }

    public boolean enabled() { return props.ac4a; }

    /** @return true if the event may be processed. */
    public boolean accept(byte[] header) {
        if (!props.ac4a) return true;
        if (header == null) {
            rejected.increment();
            log.debug("[ac4a] event without token dropped");
            return false;
        }
        try {
            jwt.verify(new String(header, StandardCharsets.UTF_8));
            accepted.increment();
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            rejected.increment();
            log.debug("[ac4a] event with invalid token dropped: {}", ex.getMessage());
            return false;
        }
    }
}
