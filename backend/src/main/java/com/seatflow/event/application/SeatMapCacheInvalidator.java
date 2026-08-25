package com.seatflow.event.application;

import com.seatflow.common.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;
import java.util.UUID;

/**
 * Drops cached seat maps when the seats underneath them change.
 * <p>
 * Ordered ahead of the WebSocket broadcaster on purpose. A client told about a
 * change immediately re-reads the map when it detects a gap in the sequence, and
 * if the cache still held the old copy that read would hand back exactly the
 * stale data the notification was meant to correct.
 * <p>
 * {@code AFTER_COMMIT} for the same reason as the broadcaster: evicting inside
 * the transaction would let a concurrent read repopulate the cache from
 * uncommitted state.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeatMapCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(SeatMapCacheInvalidator.class);

    private final CacheManager cacheManager;

    public SeatMapCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatStatusChanged(SeatStatusChanged change) {
        UUID eventId = change.eventId();

        // Both visibility variants, because the key includes that flag.
        evict(CacheConfig.SEAT_MAPS, eventId + ":true");
        evict(CacheConfig.SEAT_MAPS, eventId + ":false");
        evict(CacheConfig.EVENT_DETAILS, eventId + ":true");
        evict(CacheConfig.EVENT_DETAILS, eventId + ":false");

        log.debug("Evicted cached seat map for event {}", eventId);
    }

    private void evict(String cacheName, String key) {
        // Failures are swallowed by the CacheErrorHandler: a missed eviction
        // expires on its own, and nothing here is authoritative.
        Objects.requireNonNull(cacheManager.getCache(cacheName)).evictIfPresent(key);
    }
}
