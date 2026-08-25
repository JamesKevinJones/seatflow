package com.seatflow.user.infrastructure;

import com.seatflow.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Callers must pass an already-normalized address
     * ({@link User#normalizeEmail}). The stored column is lowercase, so a raw
     * user-supplied string would miss.
     */
    Optional<User> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    /**
     * Non-blocking advisory lock, released at commit. Its own key, distinct from
     * the expiry sweeper's and the outbox retention sweep's.
     * <p>
     * Used only by the admin bootstrap, so that several instances starting at
     * once do not all decide the account is missing and race to create it.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryLock(@Param("key") long key);
}
