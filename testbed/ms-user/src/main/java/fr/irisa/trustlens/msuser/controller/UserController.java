package fr.irisa.trustlens.msuser.controller;

import fr.irisa.trustlens.msuser.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Exposes two endpoints:
 *   POST /auth/login   — issues a JWT (used by the load test)
 *   GET  /users/{id}   — returns user profile (PEP demo endpoint)
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final JwtUtil jwtUtil;

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody Map<String, String> body) {

        String username = body.getOrDefault("username", "user");
        String device   = body.getOrDefault("device",
            UUID.randomUUID().toString());

        String token = jwtUtil.generateToken(username, device);
        return ResponseEntity.ok(Map.of(
            "token",  token,
            "userId", username
        ));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, String>> getUser(@PathVariable String id) {
        // In a real system, fetch from a user store.
        return ResponseEntity.ok(Map.of(
            "userId",   id,
            "status",   "active",
            "service",  "ms-user"
        ));
    }
}
