package fr.irisa.trustlens.zt;

import io.jsonwebtoken.security.Keys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** ZT_SR=false — a single static secret read from the environment. */
@Component
@ConditionalOnProperty(name = "ZT_SR", havingValue = "false", matchIfMissing = true)
public class StaticJwtKeyProvider implements JwtKeyProvider {

    private final VersionedKey key;

    public StaticJwtKeyProvider(ZtProperties props) {
        this.key = new VersionedKey(0,
            Keys.hmacShaKeyFor(props.staticSecret.getBytes(StandardCharsets.UTF_8)));
    }

    @Override public VersionedKey current()          { return key; }
    @Override public javax.crypto.SecretKey resolve(int version) { return key.key(); }
}
