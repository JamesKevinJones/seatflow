package com.seatflow.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for failures the API reports on purpose.
 * <p>
 * Extra context added with {@link #with} becomes a top-level extension member on
 * the RFC 9457 response body. That is how the reservation endpoint will report
 * exactly which seats were lost, rather than just saying "conflict".
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    /** Adds an extension member to the problem body. Returns {@code this} to chain. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
