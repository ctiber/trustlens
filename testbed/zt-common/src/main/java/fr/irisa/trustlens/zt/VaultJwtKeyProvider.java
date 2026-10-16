package fr.irisa.trustlens.zt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * p4 — Secret Rotation (ZT_SR=true).
 *
 * Mirrors the ZT-Tax SR primitive: the JWT signing secret is not read from the
 * environment but fetched from HashiCorp Vault (KV v2) at startup, with retries
 * while Vault is being bootstrapped, and renewed periodically.
 *
 * TrustLens extends it with an actual rotation (vault-init rewrites the secret
 * every T_rot = ZT_SR_BASE_PERIOD_S / a4 seconds). Each KV version is a key
 * version (JWT {@code kid}); a service accepts the current and the previous
 * version only, so a token captured before two rotations is rejected
 * (credential-replay defence, Scenario B).
 */
@Component
@ConditionalOnProperty(name = "ZT_SR", havingValue = "true")
public class VaultJwtKeyProvider implements JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultJwtKeyProvider.class);
    private static final int    GRACE_VERSIONS = 1;

    private final ZtProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient   http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewer =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zt-sr-renewal");
            t.setDaemon(true);
            return t;
        });
    private final Counter fetches;
    private final Counter staleRejections;

    private volatile int latest = -1;

    public VaultJwtKeyProvider(ZtProperties props, MeterRegistry registry) {
        this.props = props;
        this.fetches = Counter.builder("zt.sr.vault.fetch").register(registry);
        this.staleRejections = Counter.builder("zt.sr.stale.rejected").register(registry);
    }

    @PostConstruct
    void init() throws InterruptedException {
        // Retry until Vault is ready (vault-init may still be running)
        for (int attempt = 1; attempt <= 40; attempt++) {
            try {
                fetch(null);
                break;
            } catch (Exception ex) {
                log.info("[sr] Vault not ready, retry {}/40: {}", attempt, ex.getMessage());
                Thread.sleep(3000);
            }
        }
        if (latest < 0) throw new IllegalStateException("[sr] Could not load JWT secret from Vault");

        long renewalMs = Math.max(1000L, props.srRotationPeriodS() * 1000L / 2);
        renewer.scheduleAtFixedRate(this::renew, renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        log.info("[sr] JWT key v{} loaded from Vault; rotation period {}s, renewal every {}ms",
            latest, props.srRotationPeriodS(), renewalMs);
    }

    @PreDestroy
    void stop() { renewer.shutdownNow(); }

    private void renew() {
        try { fetch(null); }
        catch (Exception ex) { log.warn("[sr] Vault renewal failed: {}", ex.getMessage()); }
    }

    /** Fetch the latest (version == null) or a specific version of the secret. */
    private synchronized SecretKey fetch(Integer version) throws Exception {
        if (version != null && keys.containsKey(version)) return keys.get(version);

        String url = props.vaultAddr + "/v1/" + props.vaultPath
            + (version != null ? "?version=" + version : "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("X-Vault-Token", props.vaultToken)
            .timeout(Duration.ofSeconds(3))
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        fetches.increment();
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Vault HTTP " + resp.statusCode());
        }
        JsonNode data = json.readTree(resp.body()).path("data");
        String secret = data.path("data").path("jwt_secret").asText(null);
        int    v      = data.path("metadata").path("version").asInt(-1);
        if (secret == null || v < 0) throw new IllegalStateException("jwt_secret missing in Vault");

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        keys.put(v, key);
        if (v > latest) {
            latest = v;
            keys.keySet().removeIf(k -> k < latest - GRACE_VERSIONS);
        }
        return key;
    }

    @Override
    public VersionedKey current() {
        int v = latest;
        return new VersionedKey(v, keys.get(v));
    }

    @Override
    public SecretKey resolve(int version) {
        if (version < latest - GRACE_VERSIONS) {
            staleRejections.increment();
            throw new SignatureException("JWT signed with superseded key v" + version
                + " (current v" + latest + ")");
        }
        SecretKey key = keys.get(version);
        if (key != null) return key;
        try {
            return fetch(version);   // newer version rotated since last renewal
        } catch (Exception ex) {
            throw new SignatureException("Unknown JWT key version " + version);
        }
    }
}
