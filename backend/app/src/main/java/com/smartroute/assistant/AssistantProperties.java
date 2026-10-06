package com.smartroute.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Assistant settings ({@code smartroute.assistant.*}).
 *
 * <p>The assistant is the one optional feature in SmartRoute: it calls a paid API, so it is off unless both
 * the flag is on and a key is present. Everything else in the system works with it off, and the API says so
 * rather than failing in a confusing way.
 *
 * @param enabled        turns the feature on; still needs {@code apiKey}
 * @param apiKey         ANTHROPIC_API_KEY. Never logged, never returned by any endpoint
 * @param model          model id, e.g. {@code claude-opus-5-5}
 * @param maxTokens      cap on one reply
 * @param maxToolRounds  how many times the model may ask for tools before it must answer. Each round is a
 *                       paid request, and a loop that never ends would be a loop that never stops charging
 * @param effort         how hard the model thinks: low, medium, high, xhigh, max
 * @param maxQuestionCharacters longest question accepted, so the input cost of one request is bounded
 */
@ConfigurationProperties(prefix = "smartroute.assistant")
public record AssistantProperties(
        @DefaultValue("false") boolean enabled,
        String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("4000") long maxTokens,
        @DefaultValue("6") int maxToolRounds,
        @DefaultValue("medium") String effort,
        @DefaultValue("1000") int maxQuestionCharacters) {

    /** True when the feature can actually run. A flag without a key is not configured, it is half-configured. */
    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Why it is off, in words a dispatcher can act on. Empty when it is on. */
    public String disabledReason() {
        if (!enabled) {
            return "The assistant is turned off (set ASSISTANT_ENABLED=true).";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "The assistant is turned on but has no API key (set ANTHROPIC_API_KEY).";
        }
        return "";
    }
}
