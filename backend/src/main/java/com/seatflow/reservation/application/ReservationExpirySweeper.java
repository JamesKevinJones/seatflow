package com.seatflow.reservation.application;

import com.seatflow.event.application.SeatStatusChanged;
import com.seatflow.event.infrastructure.EventSeatRepository;
import com.seatflow.reservation.infrastructure.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns lapsed holds to the pool.
 * <p>
 * <b>This job is not what makes the system correct.</b> The hold query already
 * treats an expired reservation's seats as claimable, so a seat becomes
 * reservable the instant its hold lapses, whether or not this ever runs. What
 * the sweeper provides is an accurate seat map for people staring at one, and
 * reservation rows whose status matches reality.
 * <p>
 * That separation is deliberate: a scheduled job is the wrong thing to hang
 * correctness on. It can be paused, fail, or lag under load - exactly when
 * contention is highest.
 */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    /**
     * Arbitrary but fixed key identifying this job's advisory lock. Any other
     * lock in the system must use a different one.
     */
    private static final long SWEEP_LOCK_KEY = 0x5EA7F10AL;

    private final ReservationRepository reservationRepository;
    private final EventSeatRepository eventSeatRepository;
    private final ApplicationEventPublisher events;

    public ReservationExpirySweeper(
            ReservationRepository reservationRepository,
            EventSeatRepository eventSeatRepository,
            ApplicationEventPublisher events) {

        this.reservationRepository = reservationRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.events = events;
    }

    /**
     * Ordering matters: free the seats first, then mark the reservations.
     * Doing it the other way round would briefly leave EXPIRED reservations
     * still stamped on RESERVED seats.
     */
    @Scheduled(
            fixedDelayString = "${seatflow.reservation.sweep-interval-ms:10000}",
            initialDelayString = "${seatflow.reservation.sweep-initial-delay-ms:15000}")
    @Transactional
    public void sweep() {
        // Non-blocking, and released when this transaction ends - so a crashed
        // instance cannot wedge the lock. If another instance holds it, skip
        // this tick rather than queue behind it.
        if (!reservationRepository.tryAdvisoryLock(SWEEP_LOCK_KEY)) {
            log.trace("Expiry sweep skipped: another instance holds the lock");
            return;
        }

        // Captured before the update, because afterwards they no longer look
        // lapsed and there would be nothing to name in the broadcast.
        Map<UUID, List<UUID>> lapsedByEvent = new LinkedHashMap<>();
        for (Object[] row : eventSeatRepository.findLapsedHolds()) {
            lapsedByEvent.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add((UUID) row[1]);
        }

        int seatsReleased = eventSeatRepository.releaseExpiredHolds();
        int reservationsExpired = reservationRepository.markLapsedAsExpired(Instant.now());

        lapsedByEvent.forEach((eventId, seatIds) ->
                events.publishEvent(SeatStatusChanged.released(eventId, seatIds)));

        if (seatsReleased > 0 || reservationsExpired > 0) {
            log.info("Expiry sweep released {} seat(s) from {} reservation(s)",
                    seatsReleased, reservationsExpired);
        }
    }
}
