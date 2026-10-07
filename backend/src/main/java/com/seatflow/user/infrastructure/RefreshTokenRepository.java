package com.seatflow.user.infrastructure;

import com.seatflow.user.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup is by hash; the raw token is never stored, so it cannot be queried. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token for one user. Used on logout, and on detecting a
     * replayed token, where the safe assumption is that the whole family is
     * compromised.
     * <p>
     * {@code clearAutomatically} matters: a bulk update bypasses the persistence
     * context, so without it any entity already loaded in this transaction would
     * still report itself as active.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now
             where t.user.id = :userId
               and t.revokedAt is null
            """)
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Housekeeping: drop tokens that expired long enough ago to be useless.
     * <p>
     * Skips a token a surviving one still names as its successor. With a fixed
     * TTL that never happens - a predecessor expires first - but if the TTL is
     * ever shortened it would, and the self-referencing foreign key would then
     * fail the whole sweep every hour instead of leaving one row for later.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM refresh_tokens t
             WHERE t.expires_at < :before
               AND NOT EXISTS (SELECT 1 FROM refresh_tokens p
                                WHERE p.replaced_by_id = t.id
                                  AND p.expires_at >= :before)
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("before") Instant before);

    /** Same transaction-scoped advisory lock the other sweepers use. */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryLock(@Param("key") long key);
}
