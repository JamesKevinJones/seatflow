package com.seatflow.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A refresh token record.
 * <p>
 * The token itself is an opaque random string that exists only in the client's
 * hands. Only its SHA-256 hash is stored here, so a database leak yields nothing
 * usable.
 * <p>
 * Tokens rotate: presenting one revokes it and issues a successor, linked by
 * {@code replacedById}. Presenting an already-revoked token means the token was
 * stolen and replayed, and the whole family for that user is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The token issued in place of this one, set when this token is rotated. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    protected RefreshToken() {
        // for JPA
    }

    public static RefreshToken issue(User user, String tokenHash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.user = user;
        token.tokenHash = tokenHash;
        token.issuedAt = Instant.now();
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Usable exactly when it is neither revoked nor past its expiry. */
    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    /** Revokes this token and records which token supersedes it. */
    public void rotateTo(RefreshToken successor) {
        revoke();
        this.replacedById = successor.getId();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RefreshToken token)) {
            return false;
        }
        return id != null && id.equals(token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", revoked=" + isRevoked() + "]";
    }
}
