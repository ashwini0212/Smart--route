package com.smartroute.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error format returned by every endpoint.
 *
 * @param fieldErrors only present for validation errors
 * @param traceId     correlation id of the request; also in the server logs and the X-Request-Id header
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }
}
