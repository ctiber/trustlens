package fr.irisa.trustlens.zt;

import javax.crypto.SecretKey;

/**
 * Source of JWT signing / verification keys. Keys are versioned: the version
 * is carried in the token header as {@code kid}, so that every service can
 * resolve the exact key that signed a token even across rotations.
 */
public interface JwtKeyProvider {

    record VersionedKey(int version, SecretKey key) {}

    /** Key used to sign newly issued tokens. */
    VersionedKey current();

    /**
     * Key for the given version.
     * @throws io.jsonwebtoken.security.SecurityException if the version has
     *         been superseded (token signed before the last rotation grace)
     *         or is unknown.
     */
    SecretKey resolve(int version);
}
