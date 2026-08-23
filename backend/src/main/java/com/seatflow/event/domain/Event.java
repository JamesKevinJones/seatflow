package com.seatflow.event.domain;

import com.seatflow.venue.domain.Venue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A performance at a venue, on a date, with its own pricing and seat states.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @Column
    private String description;

    @Column(nullable = false)
    private String category;

    @Column(name = "poster_url")
    private String posterUrl;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "sales_start_at")
    private Instant salesStartAt;

    @Column(name = "sales_end_at")
    private Instant salesEndAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "reservation_hold_seconds", nullable = false)
    private int reservationHoldSeconds = 600;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Event() {
        // for JPA
    }

    public static Event create(
            Venue venue,
            String name,
            String category,
            Instant startsAt,
            Instant endsAt,
            String description,
            String posterUrl) {

        Event event = new Event();
        event.venue = venue;
        event.name = name.trim();
        event.slug = slugify(name);
        event.category = category.trim().toUpperCase(Locale.ROOT);
        event.startsAt = startsAt;
        event.endsAt = endsAt;
        event.description = description;
        event.posterUrl = posterUrl;
        return event;
    }

    /**
     * URL-friendly identifier derived from the name. Uniqueness is enforced by
     * {@code uq_events_slug}; the service appends a discriminator on collision.
     */
    public static String slugify(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return slug.isEmpty() ? "event" : slug;
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

    public void publish() {
        this.status = EventStatus.PUBLISHED;
    }

    public void cancel() {
        this.status = EventStatus.CANCELLED;
    }

    public void setSalesWindow(Instant from, Instant to) {
        this.salesStartAt = from;
        this.salesEndAt = to;
    }

    public void setReservationHold(Duration hold) {
        this.reservationHoldSeconds = (int) hold.toSeconds();
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    /** How long a hold on this event's seats survives without payment. */
    public Duration reservationHold() {
        return Duration.ofSeconds(reservationHoldSeconds);
    }

    /**
     * Whether seats can be reserved right now: the event is published and the
     * clock is inside its sales window.
     */
    public boolean isOnSale(Instant now) {
        if (!status.isBookable()) {
            return false;
        }
        if (salesStartAt != null && now.isBefore(salesStartAt)) {
            return false;
        }
        return salesEndAt == null || now.isBefore(salesEndAt);
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

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Instant getSalesStartAt() {
        return salesStartAt;
    }

    public Instant getSalesEndAt() {
        return salesEndAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public int getReservationHoldSeconds() {
        return reservationHoldSeconds;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Event event)) {
            return false;
        }
        return id != null && id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Event[" + name + " @ " + startsAt + "]";
    }
}
