package com.smartroute.observability;

import com.smartroute.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One log line per request: method, path, status, duration and who made it.
 *
 * <p>Ordered after Spring Security so the authenticated user is known by the time the line is written — the
 * correlation id filter runs first and has already put the trace id in the MDC, so the two join up.
 *
 * <p>What is deliberately <em>not</em> logged: the query string (it can carry filter values and, if anyone ever
 * puts one there, a token), request bodies, and the user's email. The user id is enough to find the account,
 * and a log that cannot leak personal data is one less thing to get right later.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.smartroute.access");
    static final String USER_MDC_KEY = "userId";

    /** Actuator probes and the event stream would otherwise dominate the log. */
    private static final String[] NOT_LOGGED = {"/actuator/health", "/actuator/prometheus", "/api/tracking/stream"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        CurrentUser.find().ifPresent(user -> MDC.put(USER_MDC_KEY, Long.toString(user.id())));
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (shouldLog(request)) {
                // One line, structured when structured logging is on: the fields are the same either way.
                log.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), millis);
            }
            MDC.remove(USER_MDC_KEY);
        }
    }

    private static boolean shouldLog(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String ignored : NOT_LOGGED) {
            if (path.startsWith(ignored)) {
                return false;
            }
        }
        return true;
    }
}
