package com.seatflow.common.security;

import com.seatflow.user.domain.Role;
import com.seatflow.user.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Mints access tokens and refresh tokens.
 *
 * <p><b>Access token</b> - a signed JWT. Self-contained, so verifying one costs
 * no database round trip. That is also why it is short-lived: there is no way to
 * revoke it before it expires.
 *
 * <p><b>Refresh token</b> - an opaque random string, not a JWT. It is a lookup
 * key into {@code refresh_tokens}, which means it <em>can</em> be revoked. Only
 * its SHA-256 hash is stored, so the database never holds a usable token.
 */
@Service
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * Builds a signed access token. The subject is the user id, not the email,
     * so a later email change does not invalidate live tokens.
     */
    public AccessToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        List<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .map(Enum::name)
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .build();

        // The header must name HS256 explicitly. Without it NimbusJwtEncoder
        // defaults to RS256 and then fails to find a matching key, because the
        // JWK source here holds a symmetric secret.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /**
     * Generates a refresh token. The caller stores {@link #hash} of the value and
     * returns the raw value to the client exactly once.
     */
    public String generateRefreshTokenValue() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt. Refresh tokens are 256 bits of cryptographic
     * randomness, so they are not brute-forceable and need no salt or work
     * factor - and lookup must be an indexed exact match, which a salted hash
     * cannot provide.
     */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plus(properties.refreshTokenTtl());
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    /** A minted access token and the moment it stops being valid. */
    public record AccessToken(String value, Instant expiresAt) {
    }
}
