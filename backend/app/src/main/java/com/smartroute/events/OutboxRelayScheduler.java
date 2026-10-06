package com.smartroute.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the outbox relay on a timer.
 *
 * <p>A separate class so the call goes through {@link OutboxRelay}'s proxy and therefore through its
 * transaction. With the schedule on the relay itself, the self-call bypassed the proxy: events were sent to
 * Kafka but never stamped as published, so every run re-sent them (the consumers' deduplication hid it).
 */
@Component
@ConditionalOnProperty(name = "smartroute.events.enabled", matchIfMissing = true)
class OutboxRelayScheduler {

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${smartroute.events.relay-interval:500}")
    void publishPending() {
        relay.relayOnce();
    }
}
