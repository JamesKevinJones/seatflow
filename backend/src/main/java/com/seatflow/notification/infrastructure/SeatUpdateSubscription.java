package com.seatflow.notification.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gets this instance subscribed to the fan-out channel, and keeps trying.
 *
 * <p>Exists so that starting the subscription cannot stop the application from
 * starting. The container it drives is deliberately not auto-started - see
 * {@link RedisFanoutConfig} - because a listener container that cannot reach
 * Redis during context refresh aborts the refresh, and a ticket platform that
 * refuses to boot because a cache is down has put the cache on the availability
 * path.
 *
 * <p>Here, an unreachable Redis is a warning and another attempt in a few
 * seconds. Meanwhile the application serves pages, sells seats, expires holds
 * and publishes domain events exactly as normal; only cross-instance live
 * updates are missing, and browsers fall back to detecting a sequence gap and
 * re-fetching over REST.
 *
 * <p>Once the container is running it manages its own reconnects, so this stops
 * doing anything.
 */
@Component
public class SeatUpdateSubscription {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateSubscription.class);

    private final RedisMessageListenerContainer container;

    /** So a Redis outage does not produce one warning per retry, forever. */
    private final AtomicBoolean warned = new AtomicBoolean();

    public SeatUpdateSubscription(RedisMessageListenerContainer container) {
        this.container = container;
    }

    @Scheduled(
            fixedDelayString = "${seatflow.fanout.subscribe-retry-ms:5000}",
            initialDelayString = "${seatflow.fanout.subscribe-initial-delay-ms:0}")
    public void ensureSubscribed() {
        if (container.isRunning()) {
            return;
        }

        try {
            container.start();
            warned.set(false);
            log.info("Subscribed to the seat update fan-out channel {}", SeatUpdateFanout.CHANNEL);
        } catch (RuntimeException e) {
            if (warned.compareAndSet(false, true)) {
                log.warn("Seat update fan-out not subscribed ({}). Live updates will not cross "
                        + "instances until Redis is reachable; everything else is unaffected. Retrying.",
                        e.getMessage());
            }
        }
    }

    /**
     * Whether the container has been started - which is <b>not</b> the same as
     * being connected.
     * <p>
     * {@code RedisMessageListenerContainer.isRunning()} reports its lifecycle
     * flag, and that flag is set even when the subscription behind it failed;
     * the container then retries on its recovery interval. So this answers "has
     * this been handed over to the container yet", and the only honest way to
     * know whether updates are arriving is that they arrive.
     * <p>
     * Named for what it actually knows, after a test asserted the other reading
     * and failed.
     */
    public boolean isStarted() {
        return container.isRunning();
    }
}
