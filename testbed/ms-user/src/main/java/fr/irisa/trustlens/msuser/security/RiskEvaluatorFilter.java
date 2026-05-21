package fr.irisa.trustlens.msuser.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that invokes the RiskEvaluator after JWT authentication.
 * Applies device fingerprint and, for location endpoints, impossible-travel
 * detection. Active in V3 profile only.
 */
@Slf4j
@Component
@Profile("v3")
@RequiredArgsConstructor
public class RiskEvaluatorFilter extends OncePerRequestFilter {

    private final RiskEvaluator riskEvaluator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         chain
    ) throws ServletException, IOException {

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String userId        = (String) auth.getPrincipal();
        String deviceFp      = (String) auth.getDetails();

        try {
            // ── Device fingerprint check ─────────────────────────────────────────
            var deviceDecision = riskEvaluator.evaluateDevice(userId, deviceFp);
            if (!deviceDecision.allowed()) {
                log.warn("[RiskFilter] DENY device — user={} reason={}",
                    userId, deviceDecision.reason());
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    deviceDecision.reason());
                return;
            }

            // ── Impossible travel check (location endpoints only) ─────────────────
            if (request.getRequestURI().contains("/location")) {
                String latParam = request.getParameter("lat");
                String lonParam = request.getParameter("lon");
                if (latParam != null && lonParam != null) {
                    try {
                        double lat = Double.parseDouble(latParam);
                        double lon = Double.parseDouble(lonParam);
                        var travelDecision = riskEvaluator.evaluateLocation(
                            userId, lat, lon
                        );
                        if (!travelDecision.allowed()) {
                            log.warn("[RiskFilter] DENY travel — user={} reason={}",
                                userId, travelDecision.reason());
                            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                travelDecision.reason());
                            return;
                        }
                    } catch (NumberFormatException ex) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid lat/lon parameters");
                        return;
                    }
                }
            }
        } catch (DataAccessException ex) {
            log.error("[RiskFilter] Redis unavailable — {}", ex.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Risk evaluation temporarily unavailable");
            return;
        }

        chain.doFilter(request, response);
    }
}
