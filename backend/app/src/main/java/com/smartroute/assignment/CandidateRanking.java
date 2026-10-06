package com.smartroute.assignment;

import java.util.List;
import java.util.Map;

/**
 * The result of ranking drivers for one order.
 *
 * @param driversInRadius drivers whose last position is within the search radius of the pickup
 * @param eligible        drivers that passed every hard rule and got an ETA
 * @param excluded        how many drivers in the radius were dropped, by reason
 * @param candidates      the best {@code k}, best first
 */
public record CandidateRanking(long orderId, String orderCode, double pickupLatitude, double pickupLongitude,
                               int driversInRadius, int eligible, Map<ExclusionReason, Integer> excluded,
                               List<Candidate> candidates, String algorithm) {

    static final String ALGORITHM = "weighted score (ETA, workload, capacity fit) [HEURISTIC]; "
            + "ETA by one Dijkstra on the reversed road graph (optimal travel time); top-k by bounded heap";

    /** A one-line explanation for "why couldn't this order be assigned?". */
    String summary() {
        if (driversInRadius == 0) {
            return "No driver within the search radius";
        }
        StringBuilder text = new StringBuilder("No eligible driver among " + driversInRadius + " in radius");
        excluded.forEach((reason, count) -> text.append("; ").append(count).append(' ')
                .append(reason.name().toLowerCase().replace('_', ' ')));
        return text.toString();
    }
}
