package com.seatflow.notification;

import com.seatflow.NoRedisTestcontainersConfiguration;
import com.seatflow.event.application.EventService;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.notification.application.SeatUpdateSequence;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis is not allowed to matter, including at startup.
 *
 * <p>This class exists because that claim stopped being true. Adding the
 * cross-instance fan-out put a listener on a
 * {@code RedisMessageListenerContainer}, and such a container opens its
 * subscription during context refresh - so an unreachable Redis threw, the
 * refresh aborted, and the application did not start. A cache had quietly become
 * an availability dependency, and nothing said so until the Redis timeout was
 * tightened and the whole suite failed to boot at once.
 *
 * <p>Every other test in this project runs with a real Redis container, which is
 * why none of them could catch it. This one points the application at a port
 * with nothing behind it and asserts the two things that must remain true:
 * <b>it starts, and it still sells seats.</b>
 *
 * <p>Port 6399 rather than a stopped container: a closed port refuses
 * immediately, so this measures the design rather than a timeout.
 */
@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6399"
})
@Import(NoRedisTestcontainersConfiguration.class)
@ActiveProfiles("test")
class RedisUnavailableIT {

    @Autowired private VenueService venueService;
    @Autowired private EventService eventService;
    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private SeatUpdateSequence sequence;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Reaching the body at all is most of the assertion: the context refreshed
     * with nothing listening on the Redis port.
     */
    @Test
    @DisplayName("The application starts with Redis unreachable")
    void applicationStartsWithoutRedis() {
        // Arriving here is most of the assertion - the context refreshed with
        // nothing behind the Redis port. This confirms it is genuinely serving.
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    /** The part that matters to a customer. */
    @Test
    @DisplayName("Seats can still be held and paid for with Redis unreachable")
    void seatsAreStillSoldWithoutRedis() {
        EventResponse event = publishedEvent(3);
        UUID user = createUser();

        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 2)), null);
        assertThat(hold.seats()).hasSize(2);

        BookingResponse booking = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));
        assertThat(booking.bookingReference()).hasSize(8);
        assertThat(seatCount(event.id(), "BOOKED")).isEqualTo(2);

        // And the domain events were still recorded - the outbox is PostgreSQL,
        // so nothing about it depends on the cache tier either.
        assertThat(outboxCountFor(booking.id())).isEqualTo(1);

        // The fan-out noticed it could not publish and delivered locally rather
        // than throwing. Without this the test would pass on a build where the
        // broadcast had been quietly removed.
        assertThat(degradedFanouts())
                .as("every seat change fell back to local delivery")
                .isPositive();
    }

    /**
     * The counter degrades rather than failing. Numbers from a local fallback
     * are not cluster-wide, which makes clients see gaps and re-fetch - which is
     * precisely what a gap is supposed to trigger.
     */
    @Test
    @DisplayName("The broadcast sequence falls back to a local counter")
    void sequenceFallsBackWithoutRedis() {
        UUID eventId = UUID.randomUUID();

        assertThat(sequence.next(eventId)).isEqualTo(1L);
        assertThat(sequence.next(eventId)).isEqualTo(2L);
        assertThat(sequence.next(UUID.randomUUID()))
                .as("a different event gets its own fallback counter")
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------- fixtures

    private EventResponse publishedEvent(int seatCount) {
        VenueResponse venue = venueService.create(new CreateVenueRequest(
                "No Redis Hall " + System.nanoTime(), "1 Test St", "Testville", "India", "Asia/Kolkata",
                List.of(new SectionSpec("Stalls", 1, List.of(new RowSpec("A", seatCount))))));

        Instant starts = Instant.now().plus(15, ChronoUnit.DAYS);
        EventResponse event = eventService.create(new CreateEventRequest(
                venue.id(), "No Redis Test " + System.nanoTime(), "music", null, null,
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
                VALUES (?, ?, 'not-a-real-hash', 'No Redis Tester')
                """, id, "noredis-" + id + "@example.com");
        return id;
    }

    private int seatCount(UUID eventId, String status) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_seats WHERE event_id = ? AND status = ?",
                Integer.class, eventId, status);
        return n == null ? 0 : n;
    }

    /** @return how many broadcasts fell back to local-only delivery. */
    private double degradedFanouts() {
        try {
            return meterRegistry.get("seatflow.fanout.degraded").counter().count();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }

    private int outboxCountFor(UUID bookingId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'booking.confirmed' AND aggregate_id = ?",
                Integer.class, bookingId);
        return n == null ? 0 : n;
    }
}
