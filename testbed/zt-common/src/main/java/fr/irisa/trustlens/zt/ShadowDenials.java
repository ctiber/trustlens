package fr.irisa.trustlens.zt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Would-be denials of primitives running in shadow mode (observation floor).
 * Exported as zt_shadow_denied_total{primitive}; the R_sec feature adds its
 * rate to the rate of HTTP 4xx responses. Sampled evaluations are weighted by
 * 1/sampling rate, so the counter estimates the number of denials over all
 * requests.
 */
@Component
public class ShadowDenials {
    private final Counter p2, p5;

    public ShadowDenials(MeterRegistry registry) {
        this.p2 = Counter.builder("zt.shadow.denied").tag("primitive", "p2").register(registry);
        this.p5 = Counter.builder("zt.shadow.denied").tag("primitive", "p5").register(registry);
    }

    public void p2()                  { p2.increment(); }
    public void p5(double sampleRate) { p5.increment(1.0 / Math.max(sampleRate, 1e-3)); }
}
