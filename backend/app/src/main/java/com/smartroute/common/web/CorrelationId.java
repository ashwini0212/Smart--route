package com.smartroute.common.web;

import org.slf4j.MDC;

/** Access to the current request's correlation id (stored in the logging MDC). */
public final class CorrelationId {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    private CorrelationId() {
    }

    /** The current correlation id, or "unknown" outside a request. */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "unknown";
    }
}
