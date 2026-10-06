-- Phase 10: live tracking.

-- What has already been reported as late, so a delivery predicted to miss its window raises one alert
-- instead of one per sweep. The row is deleted when the delivery is no longer late (or finishes), which is
-- also how a second alert becomes possible if it falls behind again.
CREATE TABLE delivery_alert (
    order_id        BIGINT      PRIMARY KEY REFERENCES delivery_order (id) ON DELETE CASCADE,
    late_by_seconds INTEGER     NOT NULL,
    reported_at     TIMESTAMPTZ NOT NULL
);

-- The last route computed for a driver, so a recalculation can say what changed instead of publishing an
-- event every time the sweep runs. One row per driver; the sequence is the stop labels in visiting order.
CREATE TABLE driver_route_snapshot (
    driver_id        BIGINT           PRIMARY KEY REFERENCES driver (id) ON DELETE CASCADE,
    network_version  BIGINT           NOT NULL,
    duration_seconds DOUBLE PRECISION NOT NULL,
    stop_sequence    VARCHAR(400)     NOT NULL,
    computed_at      TIMESTAMPTZ      NOT NULL
);
