package com.seatflow.reservation.presentation;

import com.seatflow.reservation.application.ReservationService;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReservationResponse;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReserveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Seat reservations. Every endpoint requires an authenticated user - browsing is
 * open, holding a seat is not.
 */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Holds seats, or fails having held none.
     *
     * @param idempotencyKey optional. Supplying one makes a retried request
     *                       return the original reservation rather than taking a
     *                       second set of seats - which matters because clients
     *                       retry, and a hold is not free.
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        ReservationResponse response =
                reservationService.reserve(userId(jwt), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<ReservationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return reservationService.findMine(userId(jwt));
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reservationId) {
        return reservationService.findById(userId(jwt), reservationId);
    }

    /** Releases the seats early. Returns the reservation in its final state. */
    @DeleteMapping("/{reservationId}")
    public ReservationResponse cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reservationId) {
        return reservationService.cancel(userId(jwt), reservationId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
