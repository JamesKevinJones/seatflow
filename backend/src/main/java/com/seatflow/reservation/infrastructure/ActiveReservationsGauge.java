package com.seatflow.reservation.infrastructure;

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
 * {@code active_reservations} - how many holds are live right now.
 * <p>
 * A gauge rather than a counter, because it can go down: holds expire, get
 * cancelled, and turn into bookings. It answers "how much of the house is
 * currently spoken for but unpaid", which is the number that predicts how many
 * seats are about to come back.
 * <p>
 * The value is cached and refreshed on a slow interval rather than read on every
 * scrape. A gauge that runs a {@code count(*)} each time Prometheus asks is a
 * self-inflicted load problem: scrape intervals are short, and this is the one
 * metric whose query touches the busiest table.
 */
@Component
public class ActiveReservationsGauge {

    private static final Logger log = LoggerFactory.getLogger(ActiveReservationsGauge.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

    private final ReservationRepository reservationRepository;
    private final MeterRegistry registry;

    private final AtomicLong cachedValue = new AtomicLong();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public ActiveReservationsGauge(ReservationRepository reservationRepository, MeterRegistry registry) {
        this.reservationRepository = reservationRepository;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("seatflow.reservations.active", this, ActiveReservationsGauge::currentValue)
                .description("Reservations currently holding seats and not yet expired")
                .register(registry);
    }

    private double currentValue() {
        Instant now = Instant.now();
        if (Duration.between(lastRefresh, now).compareTo(REFRESH_INTERVAL) < 0) {
            return cachedValue.get();
        }
        try {
            cachedValue.set(reservationRepository.countLive(now));
            lastRefresh = now;
        } catch (RuntimeException e) {
            // A metric must never break the thing it measures. Serve the last
            // known value and move on.
            log.warn("Could not refresh active reservation count: {}", e.getMessage());
        }
        return cachedValue.get();
    }
}
