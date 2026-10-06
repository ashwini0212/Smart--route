package com.smartroute.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Duration;

/**
 * Builds the model client, and only when it can.
 *
 * <p>No key means no bean, which means {@link AssistantService} has no model and reports the feature as off.
 * That is the whole of "disabled cleanly": the application starts, every other endpoint works, the assistant
 * endpoint answers 503 with the reason, and the UI hides the page. Building a client with a blank key instead
 * would turn a configuration mistake into an error the user only sees when they ask a question.
 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    @Bean
    @Conditional(Configured.class)
    AssistantModel assistantModel(AssistantProperties properties) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                // A dispatcher is waiting on this request, and the loop may make several. Generous enough for
                // a thinking model, short enough that a hung provider does not hold a servlet thread for the
                // SDK's default ten minutes.
                .timeout(Duration.ofSeconds(90))
                .build();
        log.info("Assistant enabled with model {} (effort {}, up to {} tool rounds)",
                properties.model(), properties.effort(), properties.maxToolRounds());
        return new AnthropicAssistantModel(client, properties);
    }

    /**
     * Both the flag and the key, read straight from the environment.
     *
     * <p>It has to be a {@code Condition} rather than {@code @ConditionalOnProperty} because the question is
     * about two properties at once, and a flag on with no key is not a configured assistant.
     */
    static class Configured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean enabled = Boolean.parseBoolean(
                    context.getEnvironment().getProperty("smartroute.assistant.enabled", "false"));
            String key = context.getEnvironment().getProperty("smartroute.assistant.api-key", "");
            boolean configured = enabled && !key.isBlank();
            if (!configured) {
                log.info("Assistant disabled: {}", enabled
                        ? "smartroute.assistant.enabled is true but no API key is set"
                        : "smartroute.assistant.enabled is false");
            }
            return configured;
        }
    }
}
