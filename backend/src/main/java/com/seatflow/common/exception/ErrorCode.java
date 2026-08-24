package com.seatflow.common.exception;

import org.springframework.http.HttpStatus;

import java.net.URI;

/**
 * Every failure the API reports deliberately, in one place.
 * <p>
 * Each value maps to an RFC 9457 {@code type} URI and a stable HTTP status, so
 * clients can branch on {@code type} rather than parsing prose.
 */
public enum ErrorCode {

    // Authentication and accounts
    EMAIL_ALREADY_REGISTERED("email-already-registered", "Email already registered", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS("invalid-credentials", "Invalid credentials", HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN("invalid-refresh-token", "Invalid refresh token", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED("account-disabled", "Account disabled", HttpStatus.FORBIDDEN),

    // Reservations
    /**
     * At least one requested seat was taken by someone else. The response
     * carries an {@code unavailableSeatIds} extension so the client can
     * re-render the map instead of discarding the whole selection.
     */
    SEAT_UNAVAILABLE("seat-unavailable", "Seat unavailable", HttpStatus.CONFLICT),
    RESERVATION_EXPIRED("reservation-expired", "Reservation expired", HttpStatus.UNPROCESSABLE_ENTITY),
    EVENT_NOT_ON_SALE("event-not-on-sale", "Event not on sale", HttpStatus.CONFLICT),

    // Generic
    VALIDATION_FAILED("validation-failed", "Validation failed", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("resource-not-found", "Resource not found", HttpStatus.NOT_FOUND),
    ACCESS_DENIED("access-denied", "Access denied", HttpStatus.FORBIDDEN),
    INTERNAL_ERROR("internal-error", "Internal error", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String BASE = "https://seatflow.dev/problems/";

    private final String slug;
    private final String title;
    private final HttpStatus status;

    ErrorCode(String slug, String title, HttpStatus status) {
        this.slug = slug;
        this.title = title;
        this.status = status;
    }

    public URI type() {
        return URI.create(BASE + slug);
    }

    public String title() {
        return title;
    }

    public HttpStatus status() {
        return status;
    }
}
