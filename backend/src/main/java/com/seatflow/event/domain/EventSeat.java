package com.seatflow.event.domain;

import com.seatflow.venue.domain.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The saleable state of one seat for one event. <b>This is the contended row.</b>
 * <p>
 * Read {@code docs/CONCURRENCY.md} before changing anything here.
 * <p>
 * Note what this class deliberately does <em>not</em> have: a {@code reserve()}
 * method. From Phase 3 the hold is taken by a single conditional UPDATE issued
 * against the database, because loading an entity, checking its status in Java,
 * and writing it back is exactly the check-then-act race the project exists to
 * prevent. Mutating helpers here would invite that mistake back.
 */
@Entity
@Table(name = "event_seats")
public class EventSeat {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventSeatStatus status = EventSeatStatus.AVAILABLE;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    /**
     * Which reservation holds this seat. A plain UUID rather than an association:
     * the reservation module owns that table, and keeping it denormalized here
     * lets the hold be claimed and stamped in one statement.
     */
    @Column(name = "held_by_reservation_id")
    private UUID heldByReservationId;

    @Column(name = "held_until")
    private Instant heldUntil;

    @Column(name = "booking_id")
    private UUID bookingId;

    /**
     * Guards ordinary JPA-managed edits, such as an admin repricing a seat. The
     * Phase 3 hold does not rely on it - see the class comment.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EventSeat() {
        // for JPA
    }

    /** Materializes a physical seat as saleable for one event. */
    public static EventSeat generate(Event event, Seat seat, long priceCents) {
        EventSeat eventSeat = new EventSeat();
        eventSeat.event = event;
        eventSeat.seat = seat;
        eventSeat.priceCents = priceCents;
        eventSeat.status = EventSeatStatus.AVAILABLE;
        return eventSeat;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Admin repricing. Only meaningful while the seat is still available. */
    public void reprice(long newPriceCents) {
        if (status != EventSeatStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Cannot reprice a seat that is " + status + "; it is already spoken for.");
        }
        this.priceCents = newPriceCents;
    }

    /**
     * Whether this seat is claimable right now, treating a lapsed hold as free.
     * <p>
     * Read-only convenience for rendering a seat map. It is <em>not</em> a
     * precondition check for reserving: by the time a caller acted on the answer
     * it could already be stale. The database predicate is the only authority.
     */
    public boolean isClaimable(Instant now) {
        return status == EventSeatStatus.AVAILABLE
                || (status == EventSeatStatus.RESERVED && heldUntil != null && heldUntil.isBefore(now));
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Seat getSeat() {
        return seat;
    }

    public EventSeatStatus getStatus() {
        return status;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public UUID getHeldByReservationId() {
        return heldByReservationId;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EventSeat eventSeat)) {
            return false;
        }
        return id != null && id.equals(eventSeat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "EventSeat[" + id + ", " + status + "]";
    }
}
