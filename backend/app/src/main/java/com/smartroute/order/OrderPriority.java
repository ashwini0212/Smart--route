package com.smartroute.order;

/** Higher weight = more urgent. Used by the dispatch queue (Phase 7). */
public enum OrderPriority {
    LOW(1), NORMAL(2), HIGH(3), URGENT(4);

    private final int weight;

    OrderPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
