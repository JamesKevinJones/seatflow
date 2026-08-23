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

    /** Housekeeping: drop tokens that expired long enough ago to be useless. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
