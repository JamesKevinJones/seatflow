package com.seatflow.venue.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A named block of seats within a venue, such as "Orchestra Left".
 * <p>
 * {@code displayOrder} controls rendering order on the seat map. It is not a
 * price tier - pricing is per event and lives on {@code EventSeat}.
 */
@Entity
@Table(name = "venue_sections")
public class VenueSection {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rowLabel ASC, seatNumber ASC")
    private List<Seat> seats = new ArrayList<>();

    protected VenueSection() {
        // for JPA
    }

    static VenueSection of(Venue venue, String name, int displayOrder) {
        VenueSection section = new VenueSection();
        section.venue = venue;
        section.name = name.trim();
        section.displayOrder = displayOrder;
        return section;
    }

    /**
     * Adds one seat. The unique constraint on
     * {@code (venue_section_id, row_label, seat_number)} is what actually
     * prevents duplicates; this method does not pre-check.
     */
    public Seat addSeat(String rowLabel, int seatNumber, Integer x, Integer y) {
        Seat seat = Seat.in(this, rowLabel, seatNumber, x, y);
        seats.add(seat);
        return seat;
    }

    /**
     * Adds a contiguous run of seats, numbered from 1.
     * <p>
     * Coordinates are laid out on a simple grid so a freshly imported venue
     * renders sensibly before anyone positions it by hand.
     */
    public void addRow(String rowLabel, int seatCount, int rowIndex) {
        for (int number = 1; number <= seatCount; number++) {
            addSeat(rowLabel, number, number, rowIndex);
        }
    }

    public int seatCount() {
        return seats.size();
    }

    public UUID getId() {
        return id;
    }

    public Venue getVenue() {
        return venue;
    }

    public String getName() {
        return name;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public List<Seat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VenueSection section)) {
            return false;
        }
        return id != null && id.equals(section.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "VenueSection[" + name + "]";
    }
}
