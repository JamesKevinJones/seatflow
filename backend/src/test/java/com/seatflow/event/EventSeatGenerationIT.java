package com.seatflow.event;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.common.exception.ApiException;
import com.seatflow.event.application.EventService;
import com.seatflow.event.domain.EventSeatStatus;
import com.seatflow.event.infrastructure.EventSeatRepository;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.event.presentation.dto.EventDtos.SectionPrice;
import com.seatflow.venue.application.VenueService;
import com.seatflow.venue.presentation.dto.VenueDtos.CreateVenueRequest;
import com.seatflow.venue.presentation.dto.VenueDtos.RowSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.SectionSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueResponse;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EventSeat generation: turning a venue's physical seats into per-event
 * saleable rows.
 * <p>
 * This is the foundation the reservation engine contends over in Phase 3. If
 * generation can produce duplicates, skip seats, or misprice them, no amount of
 * concurrency control downstream can recover.
 * <p>
 * Deliberately not {@code @Transactional}: these assertions are about what is
 * actually committed, and a test-managed transaction would hide it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EventSeatGenerationIT {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private VenueService venueService;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventSeatRepository eventSeatRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generatesExactlyOneSeatPerPhysicalSeat() {
        VenueResponse venue = createVenue(List.of(
                section("Orchestra", 1, List.of(row("A", 10), row("B", 10))),
                section("Balcony", 2, List.of(row("A", 5)))));

        assertThat(venue.totalSeats()).isEqualTo(25);

        EventResponse event = createEvent(venue.id(), 250_000L, List.of());

        assertThat(event.availability().total()).isEqualTo(25);
        assertThat(event.availability().available()).isEqualTo(25);
        assertThat(event.availability().reserved()).isZero();
        assertThat(event.availability().booked()).isZero();

        assertThat(eventSeatRepository.countByEventId(event.id())).isEqualTo(25);
        assertThat(eventSeatRepository.countByEventIdAndStatus(event.id(), EventSeatStatus.AVAILABLE))
                .isEqualTo(25);
    }

    @Test
    void appliesSectionPriceOverridesAndDefaultsElsewhere() {
        VenueResponse venue = createVenue(List.of(
                section("Orchestra", 1, List.of(row("A", 4))),
                section("Balcony", 2, List.of(row("A", 6)))));

        EventResponse event = createEvent(
                venue.id(), 250_000L, List.of(new SectionPrice("Balcony", 120_000L)));

        Long orchestra = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                  JOIN venue_sections sec ON sec.id = s.venue_section_id
                 WHERE es.event_id = ? AND sec.name = 'Orchestra' AND es.price_cents = 250000
                """, Long.class, event.id());

        Long balcony = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                  JOIN venue_sections sec ON sec.id = s.venue_section_id
                 WHERE es.event_id = ? AND sec.name = 'Balcony' AND es.price_cents = 120000
                """, Long.class, event.id());

        assertThat(orchestra).isEqualTo(4);
        assertThat(balcony).isEqualTo(6);
    }

    /**
     * The unique constraint, not application logic, is what makes generation
     * safe to attempt twice. Asserting it directly means a future refactor that
     * drops the pre-check still cannot create a duplicate.
     */
    @Test
    void databaseRefusesTwoRowsForTheSameSeatAndEvent() {
        VenueResponse venue = createVenue(List.of(section("Orchestra", 1, List.of(row("A", 3)))));
        EventResponse event = createEvent(venue.id(), 100_000L, List.of());

        UUID seatId = jdbcTemplate.queryForObject(
                "SELECT seat_id FROM event_seats WHERE event_id = ? LIMIT 1", UUID.class, event.id());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO event_seats (id, event_id, seat_id, status, price_cents, version, updated_at)
                VALUES (?, ?, ?, 'AVAILABLE', 100000, 0, now())
                """, UUID.randomUUID(), event.id(), seatId))
                .hasMessageContaining("uq_event_seat");
    }

    @Test
    void refusesToPublishAnEventThatHasNoSeats() {
        // An event with no seats would appear bookable and sell nothing.
        VenueResponse venue = createVenue(List.of(section("Orchestra", 1, List.of(row("A", 2)))));
        EventResponse event = createEvent(venue.id(), 100_000L, List.of());

        jdbcTemplate.update("DELETE FROM event_seats WHERE event_id = ?", event.id());

        assertThatThrownBy(() -> eventService.publish(event.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no seats");
    }

    @Test
    void draftEventsAreNotPubliclyVisible() {
        VenueResponse venue = createVenue(List.of(section("Orchestra", 1, List.of(row("A", 2)))));
        EventResponse event = createEvent(venue.id(), 100_000L, List.of());

        assertThat(event.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> eventService.findById(event.id(), false))
                .isInstanceOf(ApiException.class);

        // The same event is visible to an admin, which is what includeUnpublished means.
        assertThat(eventService.findById(event.id(), true).id()).isEqualTo(event.id());
    }

    // ------------------------------------------------------------- fixtures

    private VenueResponse createVenue(List<SectionSpec> sections) {
        int n = COUNTER.incrementAndGet();
        return venueService.create(new CreateVenueRequest(
                "Test Venue " + n + "-" + System.nanoTime(),
                "1 Test Street", "Testville", "India", "Asia/Kolkata",
                sections));
    }

    private EventResponse createEvent(UUID venueId, long defaultPrice, List<SectionPrice> overrides) {
        Instant starts = Instant.now().plus(30, ChronoUnit.DAYS);
        return eventService.create(new CreateEventRequest(
                venueId,
                "Test Event " + COUNTER.incrementAndGet(),
                "music",
                "Generated by an integration test.",
                null,
                starts,
                starts.plus(3, ChronoUnit.HOURS),
                null, null, null,
                defaultPrice,
                overrides));
    }

    private static SectionSpec section(String name, int order, List<RowSpec> rows) {
        return new SectionSpec(name, order, rows);
    }

    private static RowSpec row(String label, int seats) {
        return new RowSpec(label, seats);
    }
}
