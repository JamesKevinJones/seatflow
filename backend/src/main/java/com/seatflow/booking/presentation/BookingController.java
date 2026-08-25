package com.seatflow.booking.presentation;

import com.seatflow.booking.application.BookingService;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Booking history. A booking is private to the person who bought it. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public List<BookingResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.findMine(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{bookingId}")
    public BookingResponse findOne(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId) {
        return bookingService.findOwned(UUID.fromString(jwt.getSubject()), bookingId);
    }
}
