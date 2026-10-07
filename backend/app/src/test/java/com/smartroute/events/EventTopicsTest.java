package com.smartroute.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTopicsTest {

    private static EventTopics topics(String singleTopic, int partitions, int replicas) {
        return new EventTopics(new EventsProperties(true, Duration.ofMillis(500), Duration.ofDays(7),
                singleTopic, true, partitions, replicas));
    }

    @Test
    void byDefaultEachStepHasItsOwnTopicAndDeadLetterTopic() {
        EventTopics topics = topics("", 3, 1);

        assertThat(topics.destination(EventType.Topics.DELIVERY_DELAYED)).isEqualTo(EventType.Topics.DELIVERY_DELAYED);
        assertThat(topics.subscriptions()).containsExactly(EventType.Topics.ALL);
        List<NewTopic> created = topics.toCreate();
        assertThat(created).hasSize(14);
        assertThat(created.getFirst().numPartitions()).isEqualTo(3);
        assertThat(created.getFirst().replicationFactor()).isEqualTo((short) 1);
        assertThat(created.get(1).name()).isEqualTo(EventType.Topics.ORDER_CREATED + ".DLT");
        assertThat(created.get(1).numPartitions()).isEqualTo(1);
    }

    @Test
    void aSingleTopicCarriesEveryEventAndNeedsTwoTopicsInAll() {
        EventTopics topics = topics(" smartroute.events ", 2, 0);

        for (EventType type : EventType.values()) {
            assertThat(topics.destination(type.topic())).isEqualTo("smartroute.events");
        }
        assertThat(topics.subscriptions()).containsExactly("smartroute.events");
        assertThat(topics.toCreate()).extracting(NewTopic::name)
                .containsExactly("smartroute.events", "smartroute.events.DLT");
        assertThat(topics.toCreate().getFirst().numPartitions()).isEqualTo(2);
    }

    @Test
    void zeroReplicasLeavesTheReplicationFactorToTheBroker() {
        // -1 is how the Kafka admin API says "use the broker's default.replication.factor".
        assertThat(topics("", 3, 0).toCreate().getFirst().replicationFactor()).isEqualTo((short) -1);
    }

    @Test
    void theAllTopicsArrayCannotBeChangedThroughSubscriptions() {
        EventTopics topics = topics("", 3, 1);
        topics.subscriptions()[0] = "something-else";
        assertThat(EventType.Topics.ALL[0]).isEqualTo(EventType.Topics.ORDER_CREATED);
    }
}
