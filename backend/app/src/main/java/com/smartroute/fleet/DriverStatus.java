package com.smartroute.fleet;

public enum DriverStatus {
    /** Not working; never receives orders. */
    OFFLINE,
    /** On shift and able to take orders. */
    AVAILABLE,
    /** On shift, currently carrying deliveries; may take more if capacity allows. */
    ON_DELIVERY,
    /** On shift but paused; does not receive new orders. */
    ON_BREAK;

    public boolean canReceiveOrders() {
        return this == AVAILABLE || this == ON_DELIVERY;
    }
}
