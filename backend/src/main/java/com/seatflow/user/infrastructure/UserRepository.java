package com.seatflow.user.infrastructure;

import com.seatflow.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
