package com.seatflow.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat inside a reservation, with the price it was quoted at.
 * <p>
 * This is the historical record of what a hold contained. It survives after the
 * seat itself is released and taken by somebody else, which is why the price is
 * snapshotted here rather than read back off {@code event_seats} later.
 * <p>
 * The seat is referenced by id rather than as an association: {@code event_seats}
 * belongs to the event module, and reaching into it from here would bypass
 * {@code SeatAllocationPort}.
 */
@Entity
@Table(name = "reservation_seats")
public class ReservationSeat {

    @EmbeddedId
    private Id id;

    @Column(name = "price_cents_at_hold", nullable = false)
    private long priceCentsAtHold;

    protected ReservationSeat() {
        // for JPA
    }

    ReservationSeat(UUID reservationId, UUID eventSeatId, long priceCentsAtHold) {
        this.id = new Id(reservationId, eventSeatId);
        this.priceCentsAtHold = priceCentsAtHold;
    }

    public UUID getReservationId() {
        return id.reservationId;
    }

    public UUID getEventSeatId() {
        return id.eventSeatId;
    }

    public long getPriceCentsAtHold() {
        return priceCentsAtHold;
    }

    /** Composite key, matching the table's primary key. */
    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "reservation_id", nullable = false)
        private UUID reservationId;

        @Column(name = "event_seat_id", nullable = false)
        private UUID eventSeatId;

        protected Id() {
            // for JPA
        }

        Id(UUID reservationId, UUID eventSeatId) {
            this.reservationId = reservationId;
            this.eventSeatId = eventSeatId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id key)) {
                return false;
            }
            return Objects.equals(reservationId, key.reservationId)
                    && Objects.equals(eventSeatId, key.eventSeatId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reservationId, eventSeatId);
        }
    }
}
