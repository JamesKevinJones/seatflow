package com.seatflow.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An attempt to pay for a reservation.
 * <p>
 * The row is written as PROCESSING <em>before</em> the gateway is called, and
 * updated after. That ordering is what makes a crash mid-payment recoverable: a
 * stranded PROCESSING row is a question that can be answered later, whereas a
 * gateway call with no local record is money that moved with nothing to point at.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    /**
     * Opens a payment already in PROCESSING. There is no window in which a
     * payment exists as PENDING and unclaimed - the partial unique index treats
     * PROCESSING as live, so creating it in that state is what reserves the
     * right to charge for this reservation.
     */
    public static Payment start(UUID reservationId, UUID userId, long amountCents) {
        Payment payment = new Payment();
        payment.reservationId = reservationId;
        payment.userId = userId;
        payment.amountCents = amountCents;
        payment.status = PaymentStatus.PROCESSING;
        return payment;
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

    public void succeed(String providerReference) {
        this.status = PaymentStatus.SUCCESS;
        this.providerReference = providerReference;
    }

    /**
     * Marks the attempt failed, which also frees the reservation for a retry:
     * {@code uq_payment_inflight} only covers PROCESSING and SUCCESS.
     */
    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Payment payment)) {
            return false;
        }
        return id != null && id.equals(payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payment[" + id + ", " + status + "]";
    }
}
