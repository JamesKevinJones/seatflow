package com.seatflow.payment.application;

import com.seatflow.booking.domain.Booking;
import com.seatflow.booking.infrastructure.BookingRepository;
import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.event.application.SeatAllocationPort;
import com.seatflow.event.application.SeatStatusChanged;
import com.seatflow.payment.domain.Payment;
import com.seatflow.payment.infrastructure.PaymentRepository;
import com.seatflow.reservation.application.ReservationHoldPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional halves of paying for a hold.
 *
 * <p>The flow is deliberately split around the gateway call:
 * <pre>
 *   begin()    - transaction: lock the hold, extend it, record PROCESSING
 *   (gateway)  - no transaction, no database connection held
 *   settle()   - transaction: confirm seats, create the booking, mark SUCCESS
 *   abandon()  - transaction: mark FAILED, release the claim for a retry
 * </pre>
 *
 * <p>The orchestration lives in {@link PaymentService}, which is not
 * transactional. Keeping the steps on a separate bean is what makes the
 * boundaries real - a transactional method calling another on {@code this}
 * bypasses the proxy and silently runs in the caller's transaction.
 */
@Service
public class PaymentLedger {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedger.class);

    /**
     * How much longer a hold survives once payment starts.
     * <p>
     * Without this, a hold with four seconds left could expire while the gateway
     * is thinking, and the customer would be charged for seats that had already
     * gone back to the pool. The grace window is what makes "the payment started
     * in time" the thing that matters.
     */
    private static final Duration PAYMENT_GRACE = Duration.ofMinutes(2);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final ReservationHoldPort reservationHold;
    private final SeatAllocationPort seatAllocation;
    private final ApplicationEventPublisher events;

    public PaymentLedger(
            PaymentRepository paymentRepository,
            BookingRepository bookingRepository,
            ReservationHoldPort reservationHold,
            SeatAllocationPort seatAllocation,
            ApplicationEventPublisher events) {

        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.reservationHold = reservationHold;
        this.seatAllocation = seatAllocation;
        this.events = events;
    }

    /** An existing booking for this reservation, if it was already paid for. */
    @Transactional(readOnly = true)
    public Optional<Booking> existingBooking(UUID reservationId) {
        return bookingRepository.findByReservationId(reservationId);
    }

    /**
     * Claims the right to charge for this hold, and buys time to do it.
     * <p>
     * Takes a row lock on the reservation first, so a simultaneous second
     * attempt waits here rather than racing. The PROCESSING payment row it
     * writes is then covered by {@code uq_payment_inflight}, which is the
     * database saying the same thing independently.
     */
    @Transactional
    public Payment begin(UUID userId, UUID reservationId) {
        ReservationHoldPort.Hold hold = reservationHold.lockForPayment(reservationId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No reservation exists with that identifier."));

        if (!hold.userId().equals(userId)) {
            // 404 rather than 403: another person's reservation id is not
            // something this caller gets to confirm the existence of.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No reservation exists with that identifier.");
        }

        if (!hold.isLive(Instant.now())) {
            throw new ApiException(ErrorCode.RESERVATION_EXPIRED,
                    "That hold has expired, and the seats have gone back to the pool.")
                    .with("reservationId", reservationId.toString())
                    .with("status", hold.status().name());
        }

        paymentRepository.findLiveForReservation(reservationId).ifPresent(live -> {
            throw new ApiException(ErrorCode.PAYMENT_IN_PROGRESS,
                    "A payment for this reservation is already in progress.")
                    .with("paymentId", live.getId().toString())
                    .with("paymentStatus", live.getStatus().name());
        });

        // Buy time before calling out, not after.
        Instant extendedUntil = Instant.now().plus(PAYMENT_GRACE);
        if (extendedUntil.isAfter(hold.expiresAt())) {
            seatAllocation.extendHold(reservationId, extendedUntil);
        }

        Payment payment = Payment.start(reservationId, userId, hold.totalCents());
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Lost the race to uq_payment_inflight. The lock should have
            // prevented this; the index is why it does not matter that it did not.
            throw new ApiException(ErrorCode.PAYMENT_IN_PROGRESS,
                    "A payment for this reservation is already in progress.", e);
        }

        log.info("Payment {} started for reservation {} ({} cents)",
                payment.getId(), reservationId, hold.totalCents());
        return payment;
    }

    /**
     * Turns a successful charge into a booking.
     * <p>
     * Everything here is one transaction: the seats flip to BOOKED, the booking
     * and its seat rows are written, the payment is marked SUCCESS and the
     * reservation COMPLETED. If any of it fails, none of it happened, and the
     * payment is left PROCESSING for {@link #abandon} to settle.
     */
    @Transactional
    public Booking settle(UUID paymentId, String providerReference) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment vanished mid-flight: " + paymentId));

        ReservationHoldPort.Hold hold = reservationHold.lockForPayment(payment.getReservationId())
                .orElseThrow(() -> new IllegalStateException("Reservation vanished mid-payment"));

        // The booking id is generated in Java because event_seats.booking_id
        // carries a foreign key to it, so the row must exist before the seats
        // can point at it.
        Booking booking = Booking.confirm(
                hold.reservationId(), paymentId, hold.userId(), hold.eventId(), hold.totalCents());
        bookingRepository.saveAndFlush(booking);

        int confirmed = seatAllocation.confirm(hold.reservationId(), booking.getId());
        if (confirmed != hold.seats().size()) {
            // The hold lapsed despite the grace window, or something released
            // it. Rolling back is what stops a customer being charged for seats
            // that are no longer theirs.
            throw new ApiException(ErrorCode.RESERVATION_EXPIRED,
                    "The hold expired while the payment was being processed.")
                    .with("reservationId", hold.reservationId().toString())
                    .with("seatsConfirmed", confirmed)
                    .with("seatsExpected", hold.seats().size());
        }

        booking.recordSeats(hold.seats().stream()
                .map(seat -> new Booking.SoldSeat(seat.eventSeatId(), seat.priceCents()))
                .toList());
        bookingRepository.saveAndFlush(booking);

        payment.succeed(providerReference);
        paymentRepository.save(payment);
        reservationHold.markCompleted(hold.reservationId());

        log.info("Booking {} confirmed for reservation {}: {} seat(s), {} cents",
                booking.getBookingReference(), hold.reservationId(), confirmed, hold.totalCents());

        events.publishEvent(SeatStatusChanged.booked(
                hold.eventId(), hold.seats().stream().map(ReservationHoldPort.SeatLine::eventSeatId).toList()));
        return booking;
    }

    /**
     * Settles a payment that will not become a booking.
     * <p>
     * Its own transaction, because it runs after {@link #settle} has rolled
     * back. Marking the payment FAILED also clears it from
     * {@code uq_payment_inflight}, so the customer can try again.
     */
    @Transactional
    public void abandon(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            payment.fail(reason);
            paymentRepository.save(payment);
            log.info("Payment {} marked failed: {}", paymentId, reason);
        });
    }
}
