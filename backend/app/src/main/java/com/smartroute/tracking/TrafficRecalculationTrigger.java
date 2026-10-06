package com.smartroute.tracking;

import com.smartroute.routing.TrafficChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FR-22: when traffic changes, the deliveries under way are re-checked at once instead of waiting for the
 * next scheduled sweep — a road that just got slower is exactly when a dispatcher needs to know.
 *
 * <p>It runs after the traffic change has committed ({@code fallbackExecution} because a traffic update can
 * also arrive outside a transaction) and the sweep opens its own transactions, so a failure here can never
 * roll the traffic change back.
 */
@Component
class TrafficRecalculationTrigger {

    private static final Logger log = LoggerFactory.getLogger(TrafficRecalculationTrigger.class);

    private final DeliveryWatch watch;

    TrafficRecalculationTrigger(DeliveryWatch watch) {
        this.watch = watch;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTrafficChanged(TrafficChangedEvent event) {
        log.debug("Traffic changed to network version {}; re-checking active deliveries", event.networkVersion());
        watch.sweep("traffic changed (network version " + event.networkVersion() + ")");
    }
}
