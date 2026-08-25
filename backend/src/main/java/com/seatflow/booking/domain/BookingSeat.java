package com.seatflow.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat on a booking.
 * <p>
 * The table carries {@code uq_booking_seat_once}, a unique index on
 * {@code event_seat_id} alone. That single index is the reason a seat cannot be
 * sold twice regardless of what the application does - insert a second row for
 * the same seat and PostgreSQL refuses it.
 */
@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @EmbeddedId
    private Id id;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    protected BookingSeat() {
        // for JPA
    }

    BookingSeat(UUID bookingId, UUID eventSeatId, long priceCents) {
        this.id = new Id(bookingId, eventSeatId);
        this.priceCents = priceCents;
    }

    public UUID getBookingId() {
        return id.bookingId;
    }

    public UUID getEventSeatId() {
        return id.eventSeatId;
    }

    public long getPriceCents() {
        return priceCents;
    }

    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "booking_id", nullable = false)
        private UUID bookingId;

        @Column(name = "event_seat_id", nullable = false)
        private UUID eventSeatId;

        protected Id() {
            // for JPA
        }

        Id(UUID bookingId, UUID eventSeatId) {
            this.bookingId = bookingId;
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
            return Objects.equals(bookingId, key.bookingId)
                    && Objects.equals(eventSeatId, key.eventSeatId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bookingId, eventSeatId);
        }
    }
}
