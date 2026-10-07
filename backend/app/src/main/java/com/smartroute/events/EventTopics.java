package com.smartroute.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Which Kafka topics events travel on: one per lifecycle step (the default), or all of them on one topic.
 *
 * <p>The one-topic layout exists for managed brokers that cap the number of topics: the free Aiven plan allows
 * five, and the default layout needs fourteen (seven topics and a dead-letter topic for each). Nothing a consumer
 * relies on changes: both consumers already subscribe to every topic and dispatch on the envelope's
 * {@code type}, records are still keyed by the aggregate id so one order's events stay in order on one
 * partition, and the outbox keeps the logical topic per row. What is lost is the ability for a future consumer
 * to subscribe to one step only without reading (and skipping) the rest.
 *
 * <p>Registered as the bean {@code eventTopics} so the listeners can name it in {@code @KafkaListener}.
 */
@Component("eventTopics")
public class EventTopics {

    private final String singleTopic;
    private final int partitions;
    private final int replicas;

    EventTopics(EventsProperties properties) {
        this.singleTopic = properties.singleTopic() == null ? "" : properties.singleTopic().strip();
        this.partitions = properties.topicPartitions();
        this.replicas = properties.topicReplicas();
    }

    boolean isSingleTopic() {
        return !singleTopic.isEmpty();
    }

    /** Where an event whose type maps to {@code logicalTopic} is actually sent. */
    String destination(String logicalTopic) {
        return isSingleTopic() ? singleTopic : logicalTopic;
    }

    /** What a consumer of every event subscribes to. */
    public String[] subscriptions() {
        return isSingleTopic() ? new String[] {singleTopic} : EventType.Topics.ALL.clone();
    }

    /** The topics the application creates at startup, each followed by its dead-letter topic. */
    List<NewTopic> toCreate() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : subscriptions()) {
            topics.add(withReplicas(TopicBuilder.name(topic).partitions(partitions)));
            // One partition for a dead-letter topic: nothing reads it at speed, and order is easier to follow.
            topics.add(withReplicas(TopicBuilder.name(topic + ".DLT").partitions(1)));
        }
        return topics;
    }

    /** Zero or less leaves the replication factor to the broker's default (a managed cluster knows best). */
    private NewTopic withReplicas(TopicBuilder builder) {
        return replicas > 0 ? builder.replicas(replicas).build() : builder.build();
    }
}
