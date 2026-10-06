package com.smartroute.tracking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the delivery watch on a timer, so a delivery that falls behind is noticed even when nothing else
 * happens in the system.
 *
 * <p>Separate from {@link DeliveryWatch} so the call goes through its proxy — the same self-invocation trap
 * that cost the outbox relay its transaction in Phase 9. Separate from
 * {@link TrafficRecalculationTrigger} because the timer is what tests turn off
 * ({@code smartroute.tracking.enabled=false}) while still exercising the traffic reaction.
 */
@Component
@ConditionalOnProperty(name = "smartroute.tracking.enabled", matchIfMissing = true)
class DeliveryWatchScheduler {

    private final DeliveryWatch watch;

    DeliveryWatchScheduler(DeliveryWatch watch) {
        this.watch = watch;
    }

    @Scheduled(fixedDelayString = "${smartroute.tracking.sweep-interval:15s}")
    void sweep() {
        watch.sweep("scheduled check");
    }
}
