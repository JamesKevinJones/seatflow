package com.seatflow.reservation.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A temporary hold on a set of seats.
 * <p>
 * A reservation does not itself decide whether it owns anything. The seats are
 * won or lost by the atomic UPDATE in {@code SeatAllocationPort.tryHold}; this
 * entity records who asked, for which seats, and until when.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /*
     * The join column is read-only here because reservation_id is already part
     * of ReservationSeat's composite key. Mapping it as writable too would have
     * Hibernate managing the same column twice.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", insertable = false, updatable = false)
    private List<ReservationSeat> seats = new ArrayList<>();

    protected Reservation() {
        // for JPA
    }

    public static Reservation open(UUID eventId, UUID userId, Instant expiresAt, String idempotencyKey) {
        Reservation reservation = new Reservation();
        reservation.eventId = eventId;
        reservation.userId = userId;
        reservation.expiresAt = expiresAt;
        reservation.idempotencyKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? null
                : idempotencyKey.trim();
        return reservation;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Records the seats this hold won, at the prices quoted. Called only after
     * the atomic claim has succeeded for every one of them.
     */
    public void recordSeats(List<SeatHold> holds) {
        holds.forEach(hold -> seats.add(
                new ReservationSeat(id, hold.eventSeatId(), hold.priceCents())));
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }

    public void complete() {
        this.status = ReservationStatus.COMPLETED;
    }

    /**
     * Whether the hold is still good. Both conditions matter: the sweeper may
     * not have run yet, so an ACTIVE row can still be past its expiry.
     */
    public boolean isLive(Instant now) {
        return status == ReservationStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public long totalCents() {
        return seats.stream().mapToLong(ReservationSeat::getPriceCentsAtHold).sum();
    }

    public List<UUID> seatIds() {
        return seats.stream().map(ReservationSeat::getEventSeatId).toList();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ReservationSeat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Reservation reservation)) {
            return false;
        }
        return id != null && id.equals(reservation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Reservation[" + id + ", " + status + "]";
    }

    /** A seat won by this hold, and what it cost at that moment. */
    public record SeatHold(UUID eventSeatId, long priceCents) {
    }
}
