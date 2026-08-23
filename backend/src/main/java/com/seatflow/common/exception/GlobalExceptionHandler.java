package com.seatflow.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Turns exceptions into RFC 9457 {@code application/problem+json} responses.
 * <p>
 * Every error the API emits goes through here, so the shape is uniform and
 * clients can branch on the {@code type} URI.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Failures the application raises deliberately. */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), ex.getMessage());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        ex.getProperties().forEach(problem::setProperty);

        if (code.status().is5xxServerError()) {
            log.error("API error {}", code, ex);
        } else {
            log.debug("API error {}: {}", code, ex.getMessage());
        }
        return problem;
    }

    /**
     * Bean-validation failures on request bodies. Reports every invalid field at
     * once rather than making the client discover them one round trip at a time.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null
                                ? "is invalid"
                                : fieldError.getDefaultMessage()))
                .toList();

        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(), "The request contains " + errors.size() + " invalid field(s).");
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errors", errors);

        return ResponseEntity.status(code.status()).body(problem);
    }

    /**
     * Authorization failures raised by method security, such as a
     * {@code @PreAuthorize} that did not match.
     * <p>
     * This handler is not optional. Method security throws
     * {@code AuthorizationDeniedException} <em>inside</em> the controller
     * invocation, so it reaches this advice before Spring Security's
     * {@code ExceptionTranslationFilter} ever sees it. Without an explicit
     * handler the catch-all below turns every legitimate 403 into a 500 - which
     * both misreports the failure and leaks that something broke rather than
     * that access was refused.
     * <p>
     * Filter-level rejections still go to
     * {@link com.seatflow.common.security.ProblemDetailAccessDeniedHandler}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ErrorCode code = ErrorCode.ACCESS_DENIED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(), "You do not have permission to perform this action.");
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());

        log.debug("Access denied on {} {}", request.getMethod(), request.getRequestURI());
        return problem;
    }

    /**
     * Anything unanticipated. The detail is deliberately generic - stack traces
     * and internal messages are logged, never returned.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
