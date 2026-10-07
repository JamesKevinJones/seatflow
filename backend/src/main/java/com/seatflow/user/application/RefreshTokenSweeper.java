package com.seatflow.user.application;

import com.seatflow.user.infrastructure.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes refresh tokens a week after they expire.
 * <p>
 * Every refresh rotates to a new row, so without this the table grows by one
 * row per session per refresh, forever. Nothing reads an expired token except
 * reuse detection, and a replayed token that has expired is rejected anyway;
 * the week is only there so a recent theft can still be investigated.
 */
@Component
public class RefreshTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenSweeper.class);

    /** Distinct from every other advisory lock key in the system. */
    private static final long SWEEP_LOCK_KEY = 0x5EA7F10CL;

    static final Duration RETENTION = Duration.ofDays(7);

    private final RefreshTokenRepository repository;

    public RefreshTokenSweeper(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Scheduled(
            fixedDelayString = "${seatflow.security.refresh-token-sweep-ms:3600000}",
            initialDelayString = "${seatflow.security.refresh-token-sweep-initial-delay-ms:60000}")
    @Transactional
    public void sweep() {
        if (!repository.tryAdvisoryLock(SWEEP_LOCK_KEY)) {
            log.trace("Refresh token sweep skipped: another instance holds the lock");
            return;
        }

        int deleted = repository.deleteExpiredBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Refresh token retention: removed {} token(s) expired before {}", deleted, RETENTION);
        }
    }
}
