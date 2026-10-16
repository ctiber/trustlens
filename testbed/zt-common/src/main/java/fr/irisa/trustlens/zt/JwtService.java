package fr.irisa.trustlens.zt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Base64;
import java.util.Date;

/** Issues and verifies versioned (kid) HS256 tokens. */
@Component
public class JwtService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JwtKeyProvider keys;
    private final ZtProperties   props;

    public JwtService(JwtKeyProvider keys, ZtProperties props) {
        this.keys  = keys;
        this.props = props;
    }

    public String generate(String userId, String device) {
        var k = keys.current();
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .header().keyId(Integer.toString(k.version())).and()
            .subject(userId)
            .claim("device", device)
            .issuedAt(new Date(now))
            .expiration(new Date(now + props.jwtExpirationMs))
            .signWith(k.key(), Jwts.SIG.HS256)   // same algorithm in every variant
            .compact();
    }

    /** Verify signature, expiry and key version; throws JwtException on failure. */
    public Claims verify(String token) {
        return Jwts.parser()
            .keyLocator(new LocatorAdapter<Key>() {
                @Override
                protected Key locate(ProtectedHeader header) {
                    String kid = header.getKeyId();
                    return keys.resolve(kid == null ? 0 : Integer.parseInt(kid));
                }
            })
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * Decode the payload WITHOUT verifying the signature — used only to give
     * the risk analyser an identity to track when p2 is disabled (as the
     * ZT-Tax gateway does with jwt.decode()).
     */
    public static JsonNode decodeUnverified(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception ex) {
            return null;
        }
    }

    public static String bearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return null;
        return authorizationHeader.substring(7);
    }
}
