package com.seatflow.reservation;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.common.exception.SeatsUnavailableException;
import com.seatflow.event.application.EventService;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.reservation.application.ReservationService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test this project exists to pass.
 * <p>
 * Many people reach for the same seat at the same instant. Exactly one gets it,
 * everyone else fails cleanly, and the database is left consistent.
 *
 * <p><b>Not {@code @Transactional}.</b> A test-managed transaction would hold
 * locks across the whole method and hide the very interleaving under test.
 * These assertions are about what is actually committed.
 *
 * <p><b>Real PostgreSQL, via Testcontainers.</b> The guarantee rests on
 * PostgreSQL re-evaluating an UPDATE's WHERE clause against the newly committed
 * row version under READ COMMITTED. H2 does not reproduce that, so an H2 run of
 * this test would pass while proving nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConcurrentReservationIT {

    private static final int CONTENDERS = 200;

    @Autowired
    private VenueService venueService;

    @Autowired
    private EventService eventService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("200 people reach for one seat: exactly one gets it")
    void exactlyOneWinnerForASingleSeat() throws Exception {
        EventResponse event = publishedEvent(rows("A", 1));
        UUID seatId = seatIdsOf(event.id()).getFirst();
        List<UUID> users = createUsers(CONTENDERS);

        Outcome outcome = stampede(users, user ->
                reservationService.reserve(user, new ReserveRequest(event.id(), List.of(seatId)), null));

        assertThat(outcome.successes.get())
                .as("exactly one reservation may succeed")
                .isEqualTo(1);
        assertThat(outcome.seatConflicts.get())
                .as("every other caller must fail with a seat conflict, not something else")
                .isEqualTo(CONTENDERS - 1);
        assertThat(outcome.unexpected)
                .as("no caller may fail for any other reason")
                .isEmpty();

        // The database is the actual verdict.
        assertThat(countSeatsWithStatus(event.id(), "RESERVED")).isEqualTo(1);
        assertThat(activeReservationCount(event.id())).isEqualTo(1);
        assertThat(reservationSeatRowCount(event.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Contention for 10 seats among 200 people leaves exactly 10 held")
    void noSeatIsHeldTwiceUnderBroadContention() throws Exception {
        EventResponse event = publishedEvent(rows("A", 10));
        List<UUID> seatIds = seatIdsOf(event.id());
        List<UUID> users = createUsers(CONTENDERS);

        // Everyone asks for a single seat, chosen round-robin, so each seat has
        // twenty people competing for it.
        AtomicInteger cursor = new AtomicInteger();
        Outcome outcome = stampede(users, user -> {
            UUID seat = seatIds.get(cursor.getAndIncrement() % seatIds.size());
            reservationService.reserve(user, new ReserveRequest(event.id(), List.of(seat)), null);
        });

        assertThat(outcome.successes.get()).isEqualTo(seatIds.size());
        assertThat(outcome.unexpected).isEmpty();
        assertThat(countSeatsWithStatus(event.id(), "RESERVED")).isEqualTo(seatIds.size());

        // The real invariant: no seat appears in two live holds.
        Integer duplicated = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                  SELECT rs.event_seat_id
                    FROM reservation_seats rs
                    JOIN reservations r ON r.id = rs.reservation_id
                   WHERE r.event_id = ? AND r.status = 'ACTIVE'
                   GROUP BY rs.event_seat_id HAVING count(*) > 1) dupes
                """, Integer.class, event.id());
        assertThat(duplicated).as("no seat may be held by two reservations").isZero();
    }

    @Test
    @DisplayName("A multi-seat request is all-or-nothing")
    void multiSeatRequestsNeverCommitPartially() throws Exception {
        EventResponse event = publishedEvent(rows("A", 4));
        List<UUID> seatIds = seatIdsOf(event.id());
        List<UUID> users = createUsers(CONTENDERS);

        // Two overlapping pairs. Whoever loses seat 2 must also give up seat 1
        // or 3 - a partial hold would be a bug.
        List<UUID> left = List.of(seatIds.get(0), seatIds.get(1));
        List<UUID> right = List.of(seatIds.get(1), seatIds.get(2));

        AtomicInteger cursor = new AtomicInteger();
        Outcome outcome = stampede(users, user -> {
            List<UUID> wanted = cursor.getAndIncrement() % 2 == 0 ? left : right;
            reservationService.reserve(user, new ReserveRequest(event.id(), wanted), null);
        });

        assertThat(outcome.successes.get())
                .as("the two requests overlap on one seat, so only one can win")
                .isEqualTo(1);
        assertThat(outcome.unexpected).isEmpty();

        // Exactly two seats held, and both belong to the same reservation.
        assertThat(countSeatsWithStatus(event.id(), "RESERVED")).isEqualTo(2);
        Integer holders = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT held_by_reservation_id) FROM event_seats
                 WHERE event_id = ? AND status = 'RESERVED'
                """, Integer.class, event.id());
        assertThat(holders).as("a partial hold would show two holders").isEqualTo(1);
    }

    @Test
    @DisplayName("A lapsed hold is claimable again without the sweeper running")
    void expiredHoldsAreReclaimableWithoutTheSweeper() {
        EventResponse event = publishedEvent(rows("A", 1));
        UUID seatId = seatIdsOf(event.id()).getFirst();
        UUID first = createUsers(1).getFirst();
        UUID second = createUsers(1).getFirst();

        reservationService.reserve(first, new ReserveRequest(event.id(), List.of(seatId)), null);
        assertThat(countSeatsWithStatus(event.id(), "RESERVED")).isEqualTo(1);

        // Push the hold into the past. The sweeper is disabled in this profile,
        // so nothing tidies up - which is the point: correctness comes from the
        // hold query's predicate, not from a scheduled job.
        jdbcTemplate.update(
                "UPDATE event_seats SET held_until = now() - interval '1 second' WHERE id = ?", seatId);

        var response = reservationService.reserve(
                second, new ReserveRequest(event.id(), List.of(seatId)), null);

        assertThat(response.seats()).hasSize(1);
        assertThat(countSeatsWithStatus(event.id(), "RESERVED"))
                .as("the seat moved to the new holder, it was not double-held")
                .isEqualTo(1);
    }

    // ----------------------------------------------------------- harness

    private record Outcome(
            AtomicInteger successes,
            AtomicInteger seatConflicts,
            ConcurrentLinkedQueue<Throwable> unexpected) {
    }

    /**
     * Releases every caller at the same instant.
     * <p>
     * The start gate is what makes this a stampede rather than a queue: without
     * it, threads trickle in as they are scheduled and rarely overlap inside the
     * critical window.
     */
    private Outcome stampede(List<UUID> users, ReserveAttempt attempt) throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users.size());

        /*
         * One thread per caller, not a smaller bounded pool.
         *
         * With fewer threads than tasks, the running workers block on the start
         * gate while the rest sit in the queue and never reach ready.countDown().
         * The gate is then never opened, and ExecutorService.close() waits on
         * threads that can never finish - the harness deadlocks before the code
         * under test is ever exercised.
         */
        try (ExecutorService pool = Executors.newFixedThreadPool(users.size())) {
            for (UUID user : users) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        // Bounded so a harness bug fails the test rather than
                        // hanging the build.
                        if (!start.await(60, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("start gate never opened");
                        }
                        attempt.run(user);
                        successes.incrementAndGet();
                    } catch (SeatsUnavailableException expected) {
                        conflicts.incrementAndGet();
                    } catch (Throwable other) {
                        unexpected.add(other);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all workers queued").isTrue();
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS)).as("all workers finished").isTrue();
        }

        return new Outcome(successes, conflicts, unexpected);
    }

    @FunctionalInterface
    private interface ReserveAttempt {
        void run(UUID userId);
    }

    // ---------------------------------------------------------- fixtures

    private List<SectionSpec> rows(String label, int seatCount) {
        return List.of(new SectionSpec("Stalls", 1, List.of(new RowSpec(label, seatCount))));
    }

    private EventResponse publishedEvent(List<SectionSpec> sections) {
        VenueResponse venue = venueService.create(new CreateVenueRequest(
                "Contention Hall " + System.nanoTime(),
                "1 Test Street", "Testville", "India", "Asia/Kolkata", sections));

        Instant starts = Instant.now().plus(20, ChronoUnit.DAYS);
        EventResponse event = eventService.create(new CreateEventRequest(
                venue.id(), "Contention Test " + System.nanoTime(), "music", null, null,
                starts, starts.plus(2, ChronoUnit.HOURS), null, null, null,
                150_000L, List.of()));

        return eventService.publish(event.id());
    }

    private List<UUID> seatIdsOf(UUID eventId) {
        return jdbcTemplate.queryForList("""
                SELECT es.id FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                 WHERE es.event_id = ?
                 ORDER BY s.row_label, s.seat_number
                """, UUID.class, eventId);
    }

    /**
     * Users are inserted directly rather than registered through AuthService:
     * BCrypt is deliberately slow, and 200 registrations would add fifteen
     * seconds to a test that is not about password hashing.
     */
    private List<UUID> createUsers(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO users (id, email, password_hash, full_name)
                    VALUES (?, ?, 'not-a-real-hash', ?)
                    """, id, "contender-" + id + "@example.com", "Contender " + i);
            ids.add(id);
        }
        return ids;
    }

    private int countSeatsWithStatus(UUID eventId, String status) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_seats WHERE event_id = ? AND status = ?",
                Integer.class, eventId, status);
        return n == null ? 0 : n;
    }

    private int activeReservationCount(UUID eventId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE event_id = ? AND status = 'ACTIVE'",
                Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private int reservationSeatRowCount(UUID eventId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reservation_seats rs
                  JOIN reservations r ON r.id = rs.reservation_id
                 WHERE r.event_id = ?
                """, Integer.class, eventId);
        return n == null ? 0 : n;
    }
}
