package com.smartroute.assistant;

import com.smartroute.common.ratelimit.PerUserLimiter;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Caps how often one user may ask the assistant a question.
 *
 * <p>This is the only endpoint in SmartRoute that costs money per call, and the cost is per request rather
 * than per CPU second, so the usual "it will just be slow" backstop does not apply. Ten questions a minute per
 * user is more than a person asks and far less than a loop would.
 */
@Component
public class AssistantLimiter extends PerUserLimiter {

    AssistantLimiter(Clock clock) {
        super(10, "Too many assistant questions; try again shortly", clock);
    }
}
