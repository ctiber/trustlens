package fr.irisa.trustlens.zt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Zero Trust feature flags — one independent switch per primitive, as in the
 * ZT-Tax replication package (variants/*.env). Any subset of primitives can be
 * enabled, which makes true one-at-a-time cost isolation possible.
 *
 *   ZT_AC4A   p2  Local identity validation: JWT verified at every service
 *   ZT_MTLS   p3  Mutual TLS on East-West traffic (configuration only, see compose)
 *   ZT_SR     p4  Secret rotation: JWT key fetched from Vault, rotated periodically
 *   ZT_RA     p5  Continuous risk evaluation: synchronous call to the RA service
 *
 * p2, p5 (and the informational p3 flag) are volatile: they can be switched at
 * runtime through ZtPostureController, which the closed-loop experiment uses to
 * apply TrustLens recommendations without restarting the services.
 *
 * Graduated primitives:
 *   ZT_SR_LEVEL  a4 in (0,1] — rotation period = ZT_SR_BASE_PERIOD_S / a4
 *   ZT_RA_LEVEL  a5 in (0,1] — fraction of requests submitted to the RA service
 */
@Component
public class ZtProperties {

    @Value("${ZT_AC4A:false}")                 public volatile boolean ac4a;
    @Value("${ZT_MTLS:false}")                 public volatile boolean mtls;
    @Value("${ZT_SR:false}")                   public boolean sr;
    @Value("${ZT_SR_LEVEL:1.0}")               public double  srLevel;
    @Value("${ZT_SR_BASE_PERIOD_S:60}")        public long    srBasePeriodS;
    @Value("${ZT_RA:false}")                   public volatile boolean ra;
    @Value("${ZT_RA_LEVEL:1.0}")               public volatile double  raLevel;
    // Observation floor (shadow mode): evaluate without enforcing, and count
    // would-be denials in zt_shadow_denied_total so that R_sec reflects them.
    @Value("${ZT_AC4A_SHADOW:false}")          public volatile boolean ac4aShadow;
    @Value("${ZT_RA_SHADOW_LEVEL:0.0}")        public volatile double  raShadowLevel;
    @Value("${RA_SERVICE_URL:http://risk-analysis:9000}")   public String raUrl;
    @Value("${RA_TIMEOUT_MS:2000}")            public long    raTimeoutMs;

    @Value("${VAULT_ADDR:http://vault:8200}")  public String vaultAddr;
    @Value("${VAULT_TOKEN:trustlens-root-token}") public String vaultToken;
    @Value("${VAULT_SECRET_PATH:secret/data/trustlens}") public String vaultPath;

    @Value("${jwt.secret:trustlens-dev-secret-key-256bit!!}") public String staticSecret;
    @Value("${jwt.expiration-ms:3600000}")    public long    jwtExpirationMs;

    /** Rotation period of the JWT signing key (seconds). */
    public long srRotationPeriodS() {
        return Math.max(1L, Math.round(srBasePeriodS / Math.max(srLevel, 0.01)));
    }

    @Override
    public String toString() {
        return String.format("ZT_AC4A=%s ZT_MTLS=%s ZT_SR=%s(a4=%.2f, T_rot=%ds) ZT_RA=%s(a5=%.2f) "
            + "shadow[p2=%s, p5=%.2f]",
            ac4a, mtls, sr, srLevel, srRotationPeriodS(), ra, raLevel, ac4aShadow, raShadowLevel);
    }
}
