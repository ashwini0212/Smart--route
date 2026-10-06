package com.smartroute.assignment;

/** Why a driver inside the search radius is not a candidate. Reported as counts with every ranking. */
public enum ExclusionReason {
    /** OFFLINE or ON_BREAK. */
    NOT_ON_SHIFT,
    /** No vehicle, or the vehicle is in maintenance / retired. */
    NO_ACTIVE_VEHICLE,
    /** The order needs a bigger vehicle class. */
    VEHICLE_TOO_SMALL,
    /** Not enough weight or volume left. */
    NOT_ENOUGH_CAPACITY,
    /** Already at the configured maximum of active deliveries. */
    TOO_MANY_DELIVERIES,
    /** Eligible, but more than {@code maxCandidates} eligible drivers were nearer in a straight line. */
    BEYOND_CANDIDATE_LIMIT,
    /** Off the road network, or no road leads from the driver to the pickup. */
    UNREACHABLE
}
