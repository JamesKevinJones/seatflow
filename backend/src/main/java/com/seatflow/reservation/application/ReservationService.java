package com.seatflow.reservation.application;

import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.common.exception.SeatsUnavailableException;
import com.seatflow.event.application.EventService;
import com.seatflow.event.application.SeatAllocationPort;
import com.seatflow.event.application.SeatStatusChanged;
import com.seatflow.event.domain.Event;
import com.seatflow.reservation.domain.Reservation;
import com.seatflow.reservation.domain.ReservationSeat;
import com.seatflow.reservation.infrastructure.ReservationRepository;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReservationResponse;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReserveRequest;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReservedSeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Holding seats.
 * <p>
 * This is the class the project exists for. The argument behind it is in
 * {@code docs/CONCURRENCY.md}; read that before changing anything here.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final SeatAllocationPort seatAllocation;
    private final EventService eventService;
    private final ApplicationEventPublisher events;

    public ReservationService(
            ReservationRepository reservationRepository,
            SeatAllocationPort seatAllocation,
            EventService eventService,
            ApplicationEventPublisher events) {

        this.reservationRepository = reservationRepository;
        this.seatAllocation = seatAllocation;
        this.eventService = eventService;
        this.events = events;
    }

    /**
     * Claims seats for a user, or fails without claiming any.
     * <p>
     * One transaction, and the whole of it hinges on a single number: how many
     * rows the conditional UPDATE actually changed. If that is not exactly the
     * number requested, somebody else won at least one seat, and everything
     * rolls back - including the seats this request did win. A partial hold is
     * never committed, because "seats A5 and A8" is one request, not two.
     */
    @Transactional
    public ReservationResponse reserve(UUID userId, ReserveRequest request, String idempotencyKey) {
        // Duplicates collapsed, order fixed. Sorting is cheap insurance: two
        // requests for an overlapping set then present their ids in the same
        // order, which keeps lock acquisition order stable.
        List<UUID> seatIds = request.seatIds().stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        if (seatIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose at least one seat.");
        }

        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            var replay = reservationRepository.findByUserIdAndIdempotencyKey(userId, key);
            if (replay.isPresent()) {
                log.debug("Idempotent replay of reservation {}", replay.get().getId());
                return toResponse(replay.get());
            }
        }

        Event event = eventService.requireEvent(request.eventId());
        Instant now = Instant.now();
        if (!event.isOnSale(now)) {
            throw new ApiException(ErrorCode.EVENT_NOT_ON_SALE,
                    "This event is not currently on sale.")
                    .with("eventId", event.getId().toString())
                    .with("status", event.getStatus().name());
        }

        Duration hold = event.reservationHold();
        Instant expiresAt = now.plus(hold);

        Reservation reservation = Reservation.open(event.getId(), userId, expiresAt, key);
        try {
            // Flushed because the seats about to be stamped carry a foreign key
            // to this row.
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request using the same idempotency
            // key. The winner's reservation is the answer.
            return reservationRepository.findByUserIdAndIdempotencyKey(userId, key)
                    .map(this::toResponse)
                    .orElseThrow(() -> e);
        }

        // ---- the whole point ----
        int claimed = seatAllocation.tryHold(event.getId(), seatIds, reservation.getId(), expiresAt);

        if (claimed != seatIds.size()) {
            // Rolls back the reservation row and any seats this attempt did win.
            // Which seats were lost is worked out by the exception handler,
            // after this transaction is gone.
            log.debug("Hold lost for event {}: claimed {} of {}", event.getId(), claimed, seatIds.size());
            throw new SeatsUnavailableException(event.getId(), seatIds);
        }

        // Every seat is ours. Snapshot what was quoted, so a later reprice
        // cannot change what this user was promised.
        List<Reservation.SeatHold> holds = seatAllocation.describe(seatIds).stream()
                .map(seat -> new Reservation.SeatHold(seat.eventSeatId(), seat.priceCents()))
                .toList();
        reservation.recordSeats(holds);
        reservationRepository.save(reservation);

        log.info("Reservation {} holds {} seat(s) for event {} until {}",
                reservation.getId(), seatIds.size(), event.getId(), expiresAt);

        // Delivered only if this transaction commits - see SeatUpdateBroadcaster.
        events.publishEvent(SeatStatusChanged.held(event.getId(), seatIds));

        return toResponse(reservation);
    }

    /**
     * Releases a hold early.
     * <p>
     * The release is scoped to this reservation, so if the sweeper already freed
     * the seats and somebody else took them, this cannot take them back.
     */
    @Transactional
    public ReservationResponse cancel(UUID userId, UUID reservationId) {
        Reservation reservation = requireOwned(userId, reservationId);

        if (reservation.getStatus().isTerminal()) {
            // Cancelling something already finished is not an error worth
            // failing on; report the state it is actually in.
            return toResponse(reservation);
        }

        List<UUID> freedSeats = reservation.seatIds();
        int released = seatAllocation.release(reservationId);
        reservation.cancel();
        reservationRepository.save(reservation);

        log.info("Reservation {} cancelled, {} seat(s) released", reservationId, released);
        if (released > 0) {
            events.publishEvent(SeatStatusChanged.released(reservation.getEventId(), freedSeats));
        }
        return toResponse(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID userId, UUID reservationId) {
        return toResponse(requireOwned(userId, reservationId));
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> findMine(UUID userId) {
        return reservationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    private Reservation requireOwned(UUID userId, UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdWithSeats(reservationId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No reservation exists with that identifier."));

        if (!reservation.getUserId().equals(userId)) {
            // 404 rather than 403. Someone else's reservation id is not
            // information this caller is entitled to confirm.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No reservation exists with that identifier.");
        }
        return reservation;
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }

    private ReservationResponse toResponse(Reservation reservation) {
        // Labels come from the event module; prices come from the hold itself,
        // because a later reprice must not change what this customer was quoted.
        Map<UUID, SeatAllocationPort.SeatDetail> details = seatAllocation
                .describe(reservation.seatIds()).stream()
                .collect(Collectors.toMap(SeatAllocationPort.SeatDetail::eventSeatId, detail -> detail));

        List<ReservedSeat> seats = reservation.getSeats().stream()
                .map(seat -> {
                    var detail = details.get(seat.getEventSeatId());
                    return new ReservedSeat(
                            seat.getEventSeatId(),
                            detail == null ? "?" : detail.label(),
                            detail == null ? "?" : detail.sectionName(),
                            seat.getPriceCentsAtHold());
                })
                .toList();

        long remaining = Math.max(0,
                Duration.between(Instant.now(), reservation.getExpiresAt()).toSeconds());

        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getStatus().name(),
                reservation.getExpiresAt(),
                reservation.getStatus().isTerminal() ? 0 : remaining,
                reservation.getSeats().stream().mapToLong(ReservationSeat::getPriceCentsAtHold).sum(),
                seats);
    }
}
