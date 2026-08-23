package com.seatflow.venue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.Objects;
import java.util.UUID;

/**
 * A physical seat in a venue. Permanent: it exists for the life of the building,
 * independent of any event.
 * <p>
 * What it costs and whether anyone can buy it are per-event questions, answered
 * by {@code EventSeat}. Nothing about availability belongs on this type.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_section_id", nullable = false)
    private VenueSection section;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "position_x")
    private Integer positionX;

    @Column(name = "position_y")
    private Integer positionY;

    protected Seat() {
        // for JPA
    }

    static Seat in(VenueSection section, String rowLabel, int seatNumber, Integer x, Integer y) {
        Seat seat = new Seat();
        seat.section = section;
        seat.rowLabel = rowLabel.trim().toUpperCase();
        seat.seatNumber = seatNumber;
        seat.positionX = x;
        seat.positionY = y;
        return seat;
    }

    /** Human-readable position, e.g. {@code A12}. Not unique across sections. */
    public String label() {
        return rowLabel + seatNumber;
    }

    public UUID getId() {
        return id;
    }

    public VenueSection getSection() {
        return section;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public Integer getPositionX() {
        return positionX;
    }

    public Integer getPositionY() {
        return positionY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Seat seat)) {
            return false;
        }
        return id != null && id.equals(seat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Seat[" + label() + "]";
    }
}
