package com.seatflow.booking.application;

import com.seatflow.booking.domain.Booking;
import com.seatflow.booking.domain.BookingSeat;
import com.seatflow.booking.infrastructure.BookingRepository;
import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.event.domain.Event;
import com.seatflow.event.domain.EventSeat;
import com.seatflow.event.infrastructure.EventRepository;
import com.seatflow.event.infrastructure.EventSeatRepository;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookedSeat;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading confirmed bookings.
 * <p>
 * Bookings are only ever created by {@code PaymentLedger.settle}, inside the
 * transaction that takes the money. There is deliberately no method here that
 * creates one: a booking that can be made without a payment is a way to give
 * away seats.
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;

    public BookingService(
            BookingRepository bookingRepository,
            EventRepository eventRepository,
            EventSeatRepository eventSeatRepository) {

        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
    }

    @Transactional(readOnly = true)
    public BookingResponse describe(UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithSeats(bookingId)
                .orElseThrow(() -> notFound());
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public BookingResponse findOwned(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithSeats(bookingId)
                .orElseThrow(() -> notFound());
        if (!booking.getUserId().equals(userId)) {
            throw notFound();
        }
        return toResponse(booking);
    }

    /** Booking history, newest first. */
    @Transactional(readOnly = true)
    public List<BookingResponse> findMine(UUID userId) {
        return bookingRepository.findMineWithSeats(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No booking exists with that identifier.");
    }

    private BookingResponse toResponse(Booking booking) {
        Event event = eventRepository.findByIdWithVenue(booking.getEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "Booking " + booking.getId() + " points at a missing event"));

        // Seat labels are read once for the whole booking rather than per row,
        // which keeps a ticket with eight seats to one extra query.
        List<UUID> seatIds = booking.getSeats().stream().map(BookingSeat::getEventSeatId).toList();
        Map<UUID, EventSeat> seatsById = new HashMap<>();
        eventSeatRepository.findAllById(seatIds).forEach(seat -> seatsById.put(seat.getId(), seat));

        List<BookedSeat> seats = booking.getSeats().stream()
                .map(line -> {
                    EventSeat seat = seatsById.get(line.getEventSeatId());
                    return new BookedSeat(
                            line.getEventSeatId(),
                            seat == null ? "?" : seat.getSeat().label(),
                            seat == null ? "?" : seat.getSeat().getSection().getName(),
                            line.getPriceCents());
                })
                .toList();

        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                event.getId(),
                event.getName(),
                event.getVenue().getName(),
                event.getStartsAt(),
                booking.getTotalCents(),
                booking.getCurrency(),
                booking.getStatus(),
                booking.getCreatedAt(),
                seats);
    }
}
