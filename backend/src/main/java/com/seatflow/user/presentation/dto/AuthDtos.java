package com.seatflow.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies for the auth endpoints.
 * <p>
 * Records, not entities. JPA types are never serialized to the wire - that would
 * leak the password hash and couple the API shape to the schema.
 */
public final class AuthDtos {

    /**
     * BCrypt only reads the first 72 bytes of input and silently ignores the
     * rest, so anything longer would be accepted at registration and then
     * effectively truncated at login. Rejecting it outright is clearer.
     */
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private AuthDtos() {
    }

    public record RegisterRequest(

            @NotBlank(message = "must be provided")
            @Email(message = "must be a valid email address")
            @Size(max = 254, message = "must be at most 254 characters")
            String email,

            // Length over composition rules, per current NIST guidance. Forcing
            // a symbol and a digit produces predictable passwords, not strong ones.
            @NotBlank(message = "must be provided")
            @Size(min = 12, max = BCRYPT_MAX_PASSWORD_BYTES,
                    message = "must be between 12 and 72 characters")
            String password,

            @NotBlank(message = "must be provided")
            @Size(max = 120, message = "must be at most 120 characters")
            String fullName) {
    }

    public record LoginRequest(

            @NotBlank(message = "must be provided")
            @Email(message = "must be a valid email address")
            String email,

            @NotBlank(message = "must be provided")
            @Size(max = BCRYPT_MAX_PASSWORD_BYTES)
            String password) {
    }

    public record RefreshRequest(

            @NotBlank(message = "must be provided")
            String refreshToken) {
    }

    /**
     * @param expiresIn seconds until the access token expires, so a client need
     *                  not decode the JWT to schedule its refresh.
     */
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            Instant accessTokenExpiresAt,
            UserResponse user) {

        public static AuthResponse of(
                String accessToken,
                String refreshToken,
                long expiresIn,
                Instant accessTokenExpiresAt,
                UserResponse user) {
            return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn, accessTokenExpiresAt, user);
        }
    }

    /** The safe projection of a user. Never carries the password hash. */
    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            List<String> roles) {
    }
}
