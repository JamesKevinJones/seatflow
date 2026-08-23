package com.seatflow.venue.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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
 * A building with a fixed seat layout.
 * <p>
 * This is the aggregate root for physical inventory: sections and seats are
 * created and removed through the venue, so a layout is always written in one
 * transaction and can never be left half-built.
 */
@Entity
@Table(name = "venues")
public class Venue {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String country;

    /** IANA zone, e.g. {@code Asia/Kolkata}. */
    @Column(nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, name ASC")
    private List<VenueSection> sections = new ArrayList<>();

    protected Venue() {
        // for JPA
    }

    public static Venue create(String name, String address, String city, String country, String timezone) {
        Venue venue = new Venue();
        venue.name = name.trim();
        venue.address = address.trim();
        venue.city = city.trim();
        venue.country = country.trim();
        venue.timezone = (timezone == null || timezone.isBlank()) ? "UTC" : timezone.trim();
        return venue;
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

    public VenueSection addSection(String name, int displayOrder) {
        VenueSection section = VenueSection.of(this, name, displayOrder);
        sections.add(section);
        return section;
    }

    public void rename(String newName) {
        this.name = newName.trim();
    }

    /** Total physical seats across every section. */
    public int totalSeats() {
        return sections.stream().mapToInt(VenueSection::seatCount).sum();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<VenueSection> getSections() {
        return Collections.unmodifiableList(sections);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Venue venue)) {
            return false;
        }
        return id != null && id.equals(venue.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Venue[" + name + ", " + city + "]";
    }
}
