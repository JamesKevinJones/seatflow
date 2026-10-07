package com.seatflow.user;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.user.application.RefreshTokenSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh tokens stop accumulating: one row per refresh used to stay forever.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RefreshTokenSweeperIT {

    @Autowired private RefreshTokenSweeper sweeper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void deletesTokensExpiredPastRetentionAndKeepsTheRest() {
        UUID user = insertUser();
        Instant now = Instant.now();
        UUID longExpired = insertToken(user, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(10)), null);
        UUID recentlyExpired = insertToken(user, now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)), null);
        UUID live = insertToken(user, now, now.plus(Duration.ofDays(30)), null);

        sweeper.sweep();

        assertThat(exists(longExpired)).isFalse();
        assertThat(exists(recentlyExpired)).isTrue();
        assertThat(exists(live)).isTrue();
    }

    /**
     * A shortened TTL can leave an old successor expired while its predecessor
     * is not. Deleting the successor would violate the foreign key and fail the
     * whole sweep; it has to be skipped instead, and everything else removed.
     */
    @Test
    void skipsAnExpiredTokenAStillLiveOneNamesAsSuccessor() {
        UUID user = insertUser();
        Instant now = Instant.now();
        UUID successor = insertToken(user, now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(10)), null);
        UUID predecessor = insertToken(user, now.minus(Duration.ofDays(21)), now.plus(Duration.ofDays(5)), successor);
        UUID unrelated = insertToken(user, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(10)), null);

        sweeper.sweep();

        assertThat(exists(successor)).isTrue();
        assertThat(exists(predecessor)).isTrue();
        assertThat(exists(unrelated)).isFalse();
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, 'x', 'Sweep Test')
                """, id, id + "@sweep.test");
        return id;
    }

    private UUID insertToken(UUID user, Instant issuedAt, Instant expiresAt, UUID replacedBy) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, issued_at, expires_at, replaced_by_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, user, "hash-" + id, Timestamp.from(issuedAt), Timestamp.from(expiresAt), replacedBy);
        return id;
    }

    private boolean exists(UUID id) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }
}
