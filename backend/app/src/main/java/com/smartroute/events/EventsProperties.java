package com.smartroute.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Event publishing settings ({@code smartroute.events.*}).
 *
 * @param enabled        false disables publishing to and consuming from Kafka (used by tests that need no
 *                       broker); events are still written to the outbox, because that happens inside the
 *                       transaction that changed the data
 * @param relayInterval  how often the outbox relay looks for unpublished events
 * @param keepPublished  how long published outbox rows are kept before being deleted
 * @param singleTopic    empty: one topic per lifecycle step. A name: every event goes to that one topic
 *                       (for brokers that cap the topic count, see {@link EventTopics})
 * @param createTopics   whether the application creates its topics at startup; turn off when the topics
 *                       are created by hand, e.g. in a managed service's console
 * @param topicPartitions partitions of each event topic the application creates
 * @param topicReplicas  replication factor of the topics it creates; zero or less uses the broker default
 */
@ConfigurationProperties(prefix = "smartroute.events")
public record EventsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration relayInterval,
        @DefaultValue("7d") Duration keepPublished,
        @DefaultValue("") String singleTopic,
        @DefaultValue("true") boolean createTopics,
        @DefaultValue("3") int topicPartitions,
        @DefaultValue("1") int topicReplicas) {
}
