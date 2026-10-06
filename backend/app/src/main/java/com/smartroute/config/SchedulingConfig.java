package com.smartroute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} work: the outbox relay (every 500 ms) and its retention cleanup (hourly).
 *
 * <p>Both are safe to run on one instance only. With several app instances they would each relay, and two
 * relays could publish the same event twice; the consumers' idempotency covers that, but a shared lock
 * (or running the relay as a single leader) would be the better answer at that point.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
