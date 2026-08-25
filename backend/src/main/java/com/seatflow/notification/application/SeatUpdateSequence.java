package com.seatflow.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-event message counter that lets a client tell it missed something.
 *
 * <p>Was a local {@code AtomicLong}, which is correct for one instance and
 * quietly wrong for two: each would start its own count, and a browser receiving
 * from both would see the numbers jump around. Redis {@code INCR} is atomic
 * across the cluster, so there is one sequence per event no matter how many
 * instances are broadcasting.
 *
 * <p><b>Why Redis and not a column.</b> The obvious database answer -
 * {@code UPDATE events SET seat_version = seat_version + 1} in the reservation
 * transaction - would serialise every concurrent hold for one event behind a
 * single row lock. That is a hotspot deliberately introduced into the exact path
 * the whole project exists to keep fast, and it would show up as a throughput
 * collapse under precisely the contention the design is built for. The counter
 * is not correctness data; it does not belong in the transaction.
 *
 * <p><b>Redis is still not allowed to matter.</b> If it is unreachable this falls
 * back to a per-instance counter. The numbers then stop being cluster-wide and
 * clients see gaps - which makes them re-fetch the map over REST, which is
 * exactly the behaviour a gap is supposed to trigger. The cost of an outage is
 * more refetching, not a wrong seat map.
 */
@Component
public class SeatUpdateSequence {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateSequence.class);

    private static final String KEY_PREFIX = "seatflow:seq:";

    /**
     * Long enough to outlive any browser session watching an event, short enough
     * that finished events do not accumulate keys forever. A counter that
     * expires and restarts costs one resync per watching client.
     */
    private static final Duration TTL = Duration.ofHours(12);

    private final StringRedisTemplate redis;

    /** Only used while Redis is unreachable. */
    private final Map<UUID, AtomicLong> localFallback = new ConcurrentHashMap<>();

    public SeatUpdateSequence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long next(UUID eventId) {
        String key = KEY_PREFIX + eventId;
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null) {
                // Refreshed on every bump, so an event being actively watched
                // never has its counter expire underneath it.
                redis.expire(key, TTL);
                return value;
            }
        } catch (RuntimeException e) {
            log.warn("Sequence unavailable from Redis for event {}, falling back to a local counter: {}",
                    eventId, e.getMessage());
        }
        return localFallback.computeIfAbsent(eventId, key2 -> new AtomicLong()).incrementAndGet();
    }
}
