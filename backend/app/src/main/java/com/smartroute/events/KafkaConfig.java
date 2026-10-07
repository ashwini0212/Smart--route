package com.smartroute.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Topics, retry policy and dead-letter handling.
 *
 * <p>Retries happen in the consumer, not by re-sending: the listener blocks, waits, and tries the same record
 * again, so the partition's order is preserved while one record is being retried. After the attempts are
 * spent the record goes to {@code <topic>.DLT} with headers describing the failure, and the consumer moves on
 * rather than blocking the partition forever on one bad message.
 *
 * <p>Back-off is exponential (200 ms, 400 ms, 800 ms, 1.6 s) because the usual cause is something temporarily
 * unavailable; retrying immediately four times would just fail four times.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);
    static final int MAX_ATTEMPTS = 4;
    /**
     * Created at startup so a fresh cluster has the topics with the intended partition count (auto-created
     * topics would get the broker default, and the partition count cannot be lowered later; the brokers here
     * have auto-creation off anyway). Three partitions by default: enough to spread load, while all events of
     * one order share a key and therefore a partition. Which topics, see {@link EventTopics}.
     *
     * <p>Declared as {@link KafkaAdmin.NewTopics} rather than a {@code List<NewTopic>} bean, which
     * {@code KafkaAdmin} does not look at: with the list, nothing was created and every publish failed with
     * UNKNOWN_TOPIC_OR_PARTITION.
     */
    @Bean
    @ConditionalOnProperty(name = "smartroute.events.create-topics", matchIfMissing = true)
    KafkaAdmin.NewTopics smartrouteTopics(EventTopics eventTopics) {
        return new KafkaAdmin.NewTopics(eventTopics.toCreate().toArray(NewTopic[]::new));
    }

    @Bean
    DefaultErrorHandler eventErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", 0));
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxAttempts(MAX_ATTEMPTS - 1);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A malformed or unknown event will never succeed, so it goes straight to the dead-letter topic.
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setRetryListeners((record, exception, attempt) ->
                log.warn("Retry {} for {}-{} offset {}: {}", attempt, record.topic(), record.partition(),
                        record.offset(), exception.toString()));
        return handler;
    }
}
