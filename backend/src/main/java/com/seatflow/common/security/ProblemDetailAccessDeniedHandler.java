package com.seatflow.common.security;

import tools.jackson.databind.ObjectMapper;
import com.seatflow.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Emits RFC 9457 when an authenticated caller lacks the required role.
 * <p>
 * Distinct from {@link ProblemDetailAuthenticationEntryPoint}: this means "we
 * know who you are, and you may not do this" (403), not "we do not know who you
 * are" (401).
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        ErrorCode code = ErrorCode.ACCESS_DENIED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(), "You do not have permission to perform this action.");
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
