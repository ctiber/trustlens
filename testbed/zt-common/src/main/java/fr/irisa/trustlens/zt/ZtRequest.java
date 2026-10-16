package fr.irisa.trustlens.zt;

import jakarta.servlet.http.HttpServletRequest;

/** Request attributes shared by the ZT filters and the controllers. */
public final class ZtRequest {
    public static final String USER   = "zt.userId";
    public static final String DEVICE = "zt.device";

    private ZtRequest() {}

    public static boolean isPublic(HttpServletRequest req) {
        String path = req.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/auth/") || path.startsWith("/zt/");
    }

    public static String userId(HttpServletRequest req) {
        Object u = req.getAttribute(USER);
        return u != null ? u.toString() : null;
    }
}
