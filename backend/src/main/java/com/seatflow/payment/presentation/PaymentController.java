package com.seatflow.payment.presentation;

import com.seatflow.payment.application.PaymentService;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import com.seatflow.payment.presentation.dto.PaymentDtos.PayRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Paying for a hold.
 * <p>
 * Returns the booking rather than the payment: what the customer wants back is
 * their seats and a reference, not a receipt for a transfer.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> pay(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PayRequest request) {

        BookingResponse booking = paymentService.pay(UUID.fromString(jwt.getSubject()), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }
}
