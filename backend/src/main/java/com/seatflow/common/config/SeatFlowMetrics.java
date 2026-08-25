package com.seatflow.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The counters worth watching in production.
 * <p>
 * These are deliberately few. A dashboard of forty metrics tells you nothing;
 * these five answer the questions that actually get asked about this system:
 * how much demand is there, how much of it is losing seat races, and is money
 * turning into bookings.
 * <p>
 * {@code active_reservations} is registered as a gauge elsewhere, because a
 * gauge has to read live state rather than be incremented.
 */
@Component
public class SeatFlowMetrics {

    private final Counter reservationRequests;
    private final Counter reservationConflicts;
    private final Counter bookingSuccess;
    private final Counter bookingFailures;

    public SeatFlowMetrics(MeterRegistry registry) {
        this.reservationRequests = Counter.builder("seatflow.reservation.requests")
                .description("Attempts to hold seats, whether or not they succeeded")
                .register(registry);

        // The interesting one. Under contention this should be most of the
        // traffic - a high conflict rate is the system working, not failing.
        // It only becomes a problem if it stays high when demand is low.
        this.reservationConflicts = Counter.builder("seatflow.reservation.conflicts")
                .description("Holds refused because another request won the seat first")
                .register(registry);

        this.bookingSuccess = Counter.builder("seatflow.booking.success")
                .description("Payments that became a confirmed booking")
                .register(registry);

        // Counts money that did not turn into a seat: declines, and charges that
        // could not be booked. Worth an alert.
        this.bookingFailures = Counter.builder("seatflow.booking.failures")
                .description("Payment attempts that did not produce a booking")
                .register(registry);
    }

    public void reservationRequested() {
        reservationRequests.increment();
    }

    public void reservationConflicted() {
        reservationConflicts.increment();
    }

    public void bookingConfirmed() {
        bookingSuccess.increment();
    }

    public void bookingFailed() {
        bookingFailures.increment();
    }
}
