package com.seatflow.reservation.presentation;

import com.seatflow.common.exception.ErrorCode;
import com.seatflow.common.exception.SeatsUnavailableException;
import com.seatflow.event.application.SeatAllocationPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a failed hold into a 409 that names the seats the caller lost.
 * <p>
 * This runs <em>after</em> the reservation transaction has rolled back and
 * released its connection, which is the only safe moment to ask which seats are
 * unavailable. Doing it inside the failed transaction would read that
 * transaction's own doomed writes, and doing it in a nested one would hold two
 * pooled connections per failure - at 200 concurrent losers, that is a pool
 * deadlock exactly when the system is under load.
 * <p>
 * Ordered ahead of the global handler so this specific case wins over the
 * generic ApiException mapping.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReservationExceptionHandler {

    private final SeatAllocationPort seatAllocation;

    public ReservationExceptionHandler(SeatAllocationPort seatAllocation) {
        this.seatAllocation = seatAllocation;
    }

    @ExceptionHandler(SeatsUnavailableException.class)
    public ProblemDetail handleSeatsUnavailable(
            SeatsUnavailableException ex, HttpServletRequest request) {

        List<UUID> unavailable =
                seatAllocation.findUnclaimable(ex.getEventId(), ex.getRequestedSeatIds());

        ErrorCode code = ErrorCode.SEAT_UNAVAILABLE;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(),
                SeatsUnavailableException.detailFor(ex.getRequestedSeatIds().size(), unavailable.size()));

        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("eventId", ex.getEventId().toString());
        problem.setProperty("requestedSeatCount", ex.getRequestedSeatIds().size());
        // The client greys these out and keeps the rest of the selection.
        problem.setProperty("unavailableSeatIds", unavailable.stream().map(UUID::toString).toList());

        return problem;
    }
}
