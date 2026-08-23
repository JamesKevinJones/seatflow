package com.seatflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Signing configuration, bound from {@code seatflow.security.jwt}.
 *
 * @param secret          HMAC signing key. Must be at least 32 bytes for HS256.
 * @param issuer          the {@code iss} claim, and what the decoder requires.
 * @param accessTokenTtl  how long an access token stays valid.
 * @param refreshTokenTtl how long a refresh token stays valid.
 */
@ConfigurationProperties(prefix = "seatflow.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("""
                    seatflow.security.jwt.secret is missing or too short.
                    HS256 needs at least %d bytes. Set the SEATFLOW_JWT_SECRET environment \
                    variable, or run with the 'local' profile for a development key.\
                    """.formatted(MIN_SECRET_BYTES));
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("seatflow.security.jwt.issuer must be set.");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalStateException("seatflow.security.jwt.access-token-ttl must be positive.");
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalStateException("seatflow.security.jwt.refresh-token-ttl must be positive.");
        }
    }
}
