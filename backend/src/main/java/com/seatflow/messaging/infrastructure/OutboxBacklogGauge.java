package com.seatflow.messaging.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code outbox_pending} - messages committed but not yet on Kafka.
 *
 * <p>The one number worth alerting on in this whole module. It is normally close
 * to zero and briefly non-zero under load; a value that climbs and does not come
 * back down means the relay is not draining, which means consumers are acting on
 * a version of the world that is falling behind. Neither the published counter
 * nor the failure counter can tell you that on its own - a stopped relay
 * increments neither.
 *
 * <p>Cached and refreshed slowly, for the same reason as
 * {@code ActiveReservationsGauge}: a {@code count(*)} on every Prometheus scrape
 * is load the metric invented for itself.
 */
@Component
public class OutboxBacklogGauge {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogGauge.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

    private final OutboxRepository repository;
    private final MeterRegistry registry;

    private final AtomicLong cachedValue = new AtomicLong();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public OutboxBacklogGauge(OutboxRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("seatflow.outbox.pending", this, OutboxBacklogGauge::currentValue)
                .description("Domain events committed to the outbox but not yet published to Kafka")
                .register(registry);
    }

    private double currentValue() {
        Instant now = Instant.now();
        if (Duration.between(lastRefresh, now).compareTo(REFRESH_INTERVAL) < 0) {
            return cachedValue.get();
        }
        try {
            cachedValue.set(repository.countPending());
            lastRefresh = now;
        } catch (RuntimeException e) {
            log.warn("Could not refresh outbox backlog: {}", e.getMessage());
        }
        return cachedValue.get();
    }
}
