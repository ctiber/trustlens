package fr.irisa.trustlens.zt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * p5 — Continuous risk evaluation (ZT_RA).
 *
 * Mirrors the ZT-Tax RA primitive: a *synchronous, blocking* HTTP call to a
 * dedicated risk-analysis service for every protected request, before business
 * logic runs. The service answers {block: true|false}; a block yields 403.
 * If the analyser is unreachable the request fails open (as in ZT-Tax).
 *
 * The call is placed at the service tier (ZT-Tax "RA-MS" placement): with a
 * call-graph depth of one this issues exactly one RA call per user request, as
 * gateway placement would, and additionally covers requests that bypass the
 * gateway (Scenario A).
 *
 * Graduated activation a5 (ZT_RA_LEVEL): fraction of requests evaluated
 * (probabilistic sampling). In shadow mode (ZT_RA_SHADOW_LEVEL, p5 not
 * enforced), a fixed fraction of USERS is evaluated and would-be blocks are
 * only counted, weighted by 1/level.
 */
@Component
@Order(20)
public class RiskAnalysisFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ZtProperties props;
    private final HttpClient   http;
    private final URI          analyzeUri;
    private final Timer        allowTimer;
    private final Timer        blockTimer;
    private final Counter      failOpen;
    private final ShadowDenials shadow;

    public RiskAnalysisFilter(ZtProperties props, MeterRegistry registry, ShadowDenials shadow) {
        this.props = props;
        this.shadow = shadow;
        this.http  = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(props.raTimeoutMs))
            .build();
        this.analyzeUri = URI.create(props.raUrl + "/analyze");
        this.allowTimer = Timer.builder("zt.ra.call").tag("decision", "allow").register(registry);
        this.blockTimer = Timer.builder("zt.ra.call").tag("decision", "block").register(registry);
        this.failOpen   = Counter.builder("zt.ra.failopen").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (ZtRequest.isPublic(request)) return true;
        if (props.ra) {   // enforcement: a5 = fraction of requests (per-request sampling)
            return props.raLevel < 1.0 && ThreadLocalRandom.current().nextDouble() >= props.raLevel;
        }
        // shadow mode: sampled per user in doFilterInternal (stateful detectors
        // such as impossible travel need every request of an observed user)
        return props.raShadowLevel <= 0;
    }

    /** Deterministic per-user sampling for the shadow observation floor. */
    private boolean shadowSampled(String userId) {
        int bucket = Math.floorMod(userId.hashCode() * 0x9E3779B9, 1000);
        return bucket < props.raShadowLevel * 1000;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        ObjectNode body = JSON.createObjectNode();
        String userId = ZtRequest.userId(req);
        Object device = req.getAttribute(ZtRequest.DEVICE);
        if (userId == null) {
            // p2 disabled: decode (not verify) the token for tracking purposes only
            String token = JwtService.bearer(req.getHeader("Authorization"));
            JsonNode payload = token != null ? JwtService.decodeUnverified(token) : null;
            if (payload != null) {
                userId = payload.path("sub").asText(null);
                device = payload.path("device").asText(null);
            }
        }
        if (!props.ra && !shadowSampled(userId != null ? userId : "anonymous")) {
            chain.doFilter(req, res);
            return;
        }
        body.put("userId", userId != null ? userId : "anonymous");
        body.put("authenticated", userId != null);
        if (device != null) body.put("device", device.toString());
        body.put("method", req.getMethod());
        body.put("path", req.getRequestURI());
        String ip = req.getHeader("X-Real-IP");
        body.put("ip", ip != null ? ip : req.getRemoteAddr());
        String lat = req.getParameter("lat"), lon = req.getParameter("lon");
        if (lat != null && lon != null) {
            try {
                body.put("lat", Double.parseDouble(lat));
                body.put("lon", Double.parseDouble(lon));
            } catch (NumberFormatException ignored) { /* let the controller answer 400 */ }
        }

        long start = System.nanoTime();
        boolean block = false;
        try {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(analyzeUri)
                    .timeout(Duration.ofMillis(props.raTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            block = resp.statusCode() == 200
                && JSON.readTree(resp.body()).path("block").asBoolean(false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failOpen.increment();
        } catch (Exception ex) {
            failOpen.increment();          // RA unavailable — fail open
        }
        (block ? blockTimer : allowTimer).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);

        if (block) {
            if (props.ra) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "request blocked by risk analysis");
                return;
            }
            shadow.p5(props.raShadowLevel);   // shadow mode: observe only
        }
        chain.doFilter(req, res);
    }
}
