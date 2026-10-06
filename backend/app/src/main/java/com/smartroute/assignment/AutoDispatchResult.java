package com.smartroute.assignment;

import com.smartroute.order.OrderPriority;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one auto-dispatch run.
 *
 * @param waitingAtStart orders in status CREATED when the run started (read up to the queue limit)
 * @param stillWaiting   orders not looked at because the run's limit was reached
 */
public record AutoDispatchResult(Instant startedAt, int waitingAtStart, int assignedCount, int notAssignedCount,
                                 int stillWaiting, long durationMillis, List<Assigned> assigned,
                                 List<Skipped> notAssigned, String algorithm) {

    public record Assigned(long orderId, String orderCode, OrderPriority priority, long driverId, String driverCode,
                           double etaSeconds, double score, int candidateRank) {
    }

    public record Skipped(long orderId, String orderCode, OrderPriority priority, String reason) {
    }
}
