package com.seatflow.payment.application;

import com.seatflow.booking.application.BookingService;
import com.seatflow.booking.domain.Booking;
import com.seatflow.common.config.SeatFlowMetrics;
import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.payment.domain.Payment;
import com.seatflow.payment.infrastructure.SimulatedPaymentGateway;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import com.seatflow.payment.presentation.dto.PaymentDtos.PayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates paying for a hold.
 * <p>
 * <b>Deliberately not {@code @Transactional}.</b> The gateway call sits in the
 * middle of this method, and a transaction spanning it would hold a database
 * connection open for the whole round trip to a third party. Under load that is
 * how a slow provider becomes an exhausted connection pool and takes the rest of
 * the application down with it.
 * <p>
 * The transactional work is in {@link PaymentLedger}, on either side of the call.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentLedger ledger;
    private final SimulatedPaymentGateway gateway;
    private final BookingService bookingService;
    private final SeatFlowMetrics metrics;

    public PaymentService(
            PaymentLedger ledger,
            SimulatedPaymentGateway gateway,
            BookingService bookingService,
            SeatFlowMetrics metrics) {

        this.ledger = ledger;
        this.gateway = gateway;
        this.bookingService = bookingService;
        this.metrics = metrics;
    }

    /**
     * Pays for a reservation and returns the resulting booking.
     * <p>
     * Idempotent by way of the reservation: one reservation yields at most one
     * booking, enforced by {@code uq_booking_reservation}. Paying twice returns
     * the first booking rather than charging again.
     */
    public BookingResponse pay(UUID userId, PayRequest request) {
        UUID reservationId = request.reservationId();

        // Already paid for. Return what exists instead of starting a second charge.
        var alreadyBooked = ledger.existingBooking(reservationId);
        if (alreadyBooked.isPresent()) {
            Booking booking = alreadyBooked.get();
            if (!booking.getUserId().equals(userId)) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No reservation exists with that identifier.");
            }
            log.debug("Payment replay for reservation {} returned booking {}",
                    reservationId, booking.getBookingReference());
            return bookingService.describe(booking.getId());
        }

        Payment payment = ledger.begin(userId, reservationId);

        SimulatedPaymentGateway.Result result =
                gateway.charge(payment.getAmountCents(), request.paymentMethod());

        if (!result.successful()) {
            metrics.bookingFailed();
            ledger.abandon(payment.getId(), result.failureReason());
            throw new ApiException(ErrorCode.PAYMENT_DECLINED, result.failureReason())
                    .with("paymentId", payment.getId().toString())
                    .with("reservationId", reservationId.toString());
        }

        try {
            Booking booking = ledger.settle(payment.getId(), result.providerReference());
            metrics.bookingConfirmed();
            return bookingService.describe(booking.getId());
        } catch (RuntimeException e) {
            // The charge went through but the booking did not. Settle the
            // payment as failed - in a real system this is also where the
            // refund would be issued - and tell the caller the truth.
            metrics.bookingFailed();
            ledger.abandon(payment.getId(),
                    "Charge accepted but the seats could not be confirmed: " + e.getMessage());
            log.error("Payment {} succeeded at the gateway but could not be booked", payment.getId(), e);
            throw e;
        }
    }
}
