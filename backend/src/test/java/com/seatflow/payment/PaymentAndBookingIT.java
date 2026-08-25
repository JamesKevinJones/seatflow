package com.seatflow.payment;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.common.exception.ApiException;
import com.seatflow.event.application.EventService;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.payment.application.PaymentService;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import com.seatflow.payment.presentation.dto.PaymentDtos.PayRequest;
import com.seatflow.reservation.application.ReservationService;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReservationResponse;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReserveRequest;
import com.seatflow.venue.application.VenueService;
import com.seatflow.venue.presentation.dto.VenueDtos.CreateVenueRequest;
import com.seatflow.venue.presentation.dto.VenueDtos.RowSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.SectionSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment, booking, and the guarantee that a seat is never sold twice.
 * <p>
 * Not {@code @Transactional}: these assertions are about committed state, and
 * the payment flow deliberately spans several transactions.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PaymentAndBookingIT {

    @Autowired private VenueService venueService;
    @Autowired private EventService eventService;
    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Paying for a hold books the seats and completes the reservation")
    void happyPath() {
        EventResponse event = publishedEvent(3);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 2)), null);

        BookingResponse booking = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));

        assertThat(booking.bookingReference()).hasSize(8);
        assertThat(booking.seats()).hasSize(2);
        assertThat(booking.totalCents()).isEqualTo(hold.totalCents());

        assertThat(seatCount(event.id(), "BOOKED")).isEqualTo(2);
        assertThat(seatCount(event.id(), "RESERVED")).isZero();
        assertThat(reservationStatus(hold.id())).isEqualTo("COMPLETED");
        assertThat(paymentStatus(hold.id())).isEqualTo("SUCCESS");

        // Every sold seat is recorded against the booking.
        assertThat(bookingSeatCount(booking.id())).isEqualTo(2);
    }

    /**
     * The backstop, asserted directly.
     * <p>
     * Everything above this could be broken and the database would still refuse
     * to sell one seat twice. Testing it against the real index means a future
     * refactor cannot quietly drop the guarantee.
     */
    @Test
    @DisplayName("The database refuses to put one seat in two bookings")
    void uniqueIndexPreventsSellingASeatTwice() {
        EventResponse event = publishedEvent(2);
        List<UUID> seats = seatIds(event.id());

        // Two genuine bookings for two different seats, so the only constraint
        // in play below is the one on event_seat_id. Reusing one reservation
        // would trip uq_booking_reservation first and prove the wrong thing.
        UUID buyerOne = createUser();
        ReservationResponse holdOne = reservationService.reserve(
                buyerOne, new ReserveRequest(event.id(), List.of(seats.get(0))), null);
        BookingResponse bookingOne = paymentService.pay(buyerOne, new PayRequest(holdOne.id(), "card_ok"));

        UUID buyerTwo = createUser();
        ReservationResponse holdTwo = reservationService.reserve(
                buyerTwo, new ReserveRequest(event.id(), List.of(seats.get(1))), null);
        BookingResponse bookingTwo = paymentService.pay(buyerTwo, new PayRequest(holdTwo.id(), "card_ok"));

        UUID seatSoldToBuyerOne = bookingOne.seats().getFirst().eventSeatId();

        // Now try to attach buyer one's seat to buyer two's booking. Every
        // application-level guard is bypassed here on purpose: this asserts the
        // database alone will not allow a seat to be sold twice.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO booking_seats (booking_id, event_seat_id, price_cents)
                VALUES (?, ?, 200000)
                """, bookingTwo.id(), seatSoldToBuyerOne))
                .hasMessageContaining("uq_booking_seat_once");
    }

    @Test
    @DisplayName("A declined payment leaves the seats held and allows a retry")
    void declinedPaymentIsRetryable() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 1)), null);

        assertThatThrownBy(() -> paymentService.pay(user, new PayRequest(hold.id(), "decline_me")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("declined");

        // Nothing sold, the hold survives, and the failed attempt is recorded.
        assertThat(seatCount(event.id(), "BOOKED")).isZero();
        assertThat(seatCount(event.id(), "RESERVED")).isEqualTo(1);
        assertThat(reservationStatus(hold.id())).isEqualTo("ACTIVE");
        assertThat(paymentStatus(hold.id())).isEqualTo("FAILED");

        // A failed payment leaves uq_payment_inflight, so a retry is allowed.
        BookingResponse booking = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));
        assertThat(booking.seats()).hasSize(1);
        assertThat(seatCount(event.id(), "BOOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("Paying twice returns the first booking rather than charging again")
    void payingTwiceIsIdempotent() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 1)), null);

        BookingResponse first = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));
        BookingResponse second = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.bookingReference()).isEqualTo(first.bookingReference());
        assertThat(bookingCount(hold.id())).isEqualTo(1);
        assertThat(successfulPaymentCount(hold.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("An expired hold cannot be paid for")
    void expiredHoldCannotBePaid() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 1)), null);

        jdbcTemplate.update("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                hold.id());
        jdbcTemplate.update("UPDATE event_seats SET held_until = now() - interval '1 minute' "
                + "WHERE held_by_reservation_id = ?", hold.id());

        assertThatThrownBy(() -> paymentService.pay(user, new PayRequest(hold.id(), "card_ok")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");

        assertThat(seatCount(event.id(), "BOOKED")).isZero();
        assertThat(bookingCount(hold.id())).isZero();
    }

    @Test
    @DisplayName("Twenty simultaneous payments for one hold produce exactly one booking")
    void concurrentPaymentsProduceOneBooking() throws Exception {
        EventResponse event = publishedEvent(3);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 2)), null);

        int attempts = 20;
        AtomicInteger succeeded = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));
                        succeeded.incrementAndGet();
                    } catch (ApiException expected) {
                        // "already in progress" is the correct answer for a
                        // caller that arrives mid-flight.
                    } catch (Throwable other) {
                        failures.add(other);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).as("no attempt may fail for an unexpected reason").isEmpty();
        assertThat(succeeded.get()).as("at least one caller is served").isPositive();

        // Whatever happened above, the ledger must show exactly one of each.
        assertThat(bookingCount(hold.id())).isEqualTo(1);
        assertThat(successfulPaymentCount(hold.id())).isEqualTo(1);
        assertThat(seatCount(event.id(), "BOOKED")).isEqualTo(2);
    }

    // ---------------------------------------------------------- fixtures

    private EventResponse publishedEvent(int seatCount) {
        VenueResponse venue = venueService.create(new CreateVenueRequest(
                "Checkout Hall " + System.nanoTime(), "1 Test St", "Testville", "India", "Asia/Kolkata",
                List.of(new SectionSpec("Stalls", 1, List.of(new RowSpec("A", seatCount))))));

        Instant starts = Instant.now().plus(15, ChronoUnit.DAYS);
        EventResponse event = eventService.create(new CreateEventRequest(
                venue.id(), "Checkout Test " + System.nanoTime(), "music", null, null,
                starts, starts.plus(2, ChronoUnit.HOURS), null, null, null, 200_000L, List.of()));
        return eventService.publish(event.id());
    }

    private List<UUID> seatIds(UUID eventId) {
        return jdbcTemplate.queryForList("""
                SELECT es.id FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                 WHERE es.event_id = ? ORDER BY s.row_label, s.seat_number
                """, UUID.class, eventId);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, 'not-a-real-hash', 'Checkout Tester')
                """, id, "checkout-" + id + "@example.com");
        return id;
    }

    private int seatCount(UUID eventId, String status) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_seats WHERE event_id = ? AND status = ?",
                Integer.class, eventId, status);
        return n == null ? 0 : n;
    }

    private String reservationStatus(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private String paymentStatus(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE reservation_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, reservationId);
    }

    private int bookingCount(UUID reservationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE reservation_id = ?", Integer.class, reservationId);
        return n == null ? 0 : n;
    }

    private int successfulPaymentCount(UUID reservationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE reservation_id = ? AND status = 'SUCCESS'",
                Integer.class, reservationId);
        return n == null ? 0 : n;
    }

    private int bookingSeatCount(UUID bookingId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking_seats WHERE booking_id = ?", Integer.class, bookingId);
        return n == null ? 0 : n;
    }
}
