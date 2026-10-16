package fr.irisa.trustlens.zt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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

/**
 * p2 — Local identity validation / Access Control on All services (ZT_AC4A).
 *
 * Every service verifies the JWT signature itself instead of trusting the
 * gateway. Runs inside the Micrometer HTTP observation, so its cost and its
 * 401 responses are reflected in T_avg and R_sec.
 */
@Component
@Order(10)
public class Ac4aFilter extends OncePerRequestFilter {

    private final ZtProperties props;
    private final JwtService   jwt;
    private final Timer        verifyTimer;
    private final ShadowDenials shadow;

    public Ac4aFilter(ZtProperties props, JwtService jwt, MeterRegistry registry,
                      ShadowDenials shadow) {
        this.props = props;
        this.jwt   = jwt;
        this.shadow = shadow;
        this.verifyTimer = Timer.builder("zt.ac4a.verify").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(props.ac4a || props.ac4aShadow) || ZtRequest.isPublic(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        boolean enforce = props.ac4a;   // otherwise shadow mode: observe only
        String token = JwtService.bearer(req.getHeader("Authorization"));
        if (token == null) {
            if (enforce) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing token");
                return;
            }
            shadow.p2();
            chain.doFilter(req, res);
            return;
        }
        Claims claims;
        long start = System.nanoTime();
        try {
            claims = jwt.verify(token);
        } catch (JwtException | IllegalArgumentException ex) {
            if (enforce) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid token");
                return;
            }
            shadow.p2();
            chain.doFilter(req, res);
            return;
        } finally {
            verifyTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        req.setAttribute(ZtRequest.USER,   claims.getSubject());
        req.setAttribute(ZtRequest.DEVICE, claims.get("device"));
        chain.doFilter(req, res);
    }
}
