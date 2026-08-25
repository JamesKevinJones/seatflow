package com.seatflow.payment.infrastructure;

import com.seatflow.payment.domain.Payment;
import com.seatflow.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * The live payment for a reservation, if any. Matches what
     * {@code uq_payment_inflight} considers live, so the application and the
     * database agree on what "already paying" means.
     */
    @Query("""
            select p from Payment p
             where p.reservationId = :reservationId
               and p.status in (com.seatflow.payment.domain.PaymentStatus.PROCESSING,
                                com.seatflow.payment.domain.PaymentStatus.SUCCESS)
            """)
    Optional<Payment> findLiveForReservation(@Param("reservationId") UUID reservationId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Payment> findByStatus(PaymentStatus status);
}
