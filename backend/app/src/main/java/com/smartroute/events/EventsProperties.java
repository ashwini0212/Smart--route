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
 */
@ConfigurationProperties(prefix = "smartroute.events")
public record EventsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration relayInterval,
        @DefaultValue("7d") Duration keepPublished) {
}
