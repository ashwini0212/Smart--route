package com.smartroute.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. Clients switch on these, never on message text. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_ROUTE_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    /** A coordinate is too far from any road of the loaded network to be snapped to it. */
    LOCATION_OFF_NETWORK(HttpStatus.UNPROCESSABLE_CONTENT),
    /** Both points are on the network but no road connects them in the travel direction. */
    NO_ROUTE(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** An optional feature was asked for while it is not configured (the assistant without an API key). */
    FEATURE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    /** A service we depend on but do not run failed or refused the request. */
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
