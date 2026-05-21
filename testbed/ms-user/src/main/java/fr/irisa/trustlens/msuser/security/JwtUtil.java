package fr.irisa.trustlens.msuser.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility — shared by V2 and V3.
 *
 * In V3, the signing key is fetched from Vault at startup and can be
 * rotated at runtime via {@link #rotateKey(String)}.
 * Requests carrying a token signed with a revoked key are rejected,
 * implementing the credential-replay defence (Scenario B).
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    private volatile SecretKey signingKey;

    // ── Key management ────────────────────────────────────────────────────────

    public SecretKey getSigningKey() {
        if (signingKey == null) {
            signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        return signingKey;
    }

    /** Called by VaultKeyRotationScheduler (V3 only) on secret renewal. */
    public void rotateKey(String newSecret) {
        log.info("[V3] JWT signing key rotated");
        signingKey = Keys.hmacShaKeyFor(newSecret.getBytes(StandardCharsets.UTF_8));
    }

    // ── Token operations ──────────────────────────────────────────────────────

    public String generateToken(String userId, String deviceFingerprint) {
        return Jwts.builder()
            .subject(userId)
            .claim("device", deviceFingerprint)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey())
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public String extractDevice(String token) {
        return (String) parseToken(token).get("device");
    }
}
