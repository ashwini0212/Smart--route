-- Phase 7: driver assignment.

-- The driver currently responsible for an order. Set together with status ASSIGNED, cleared when the
-- order goes back to the queue; kept on terminal orders as a record of who delivered (or failed) it.
ALTER TABLE delivery_order ADD COLUMN driver_id BIGINT REFERENCES driver (id);
ALTER TABLE delivery_order ADD COLUMN assigned_at TIMESTAMPTZ;
ALTER TABLE delivery_order ADD CONSTRAINT chk_order_driver_when_assigned
    CHECK (status NOT IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT') OR driver_id IS NOT NULL);

-- "Active deliveries of driver X" (driver app, offline re-queue, workload).
CREATE INDEX idx_order_driver_active ON delivery_order (driver_id)
    WHERE status IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT');

-- Every assignment decision, with the score breakdown that justified it (audit, analytics, explanations).
CREATE TABLE assignment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         BIGINT        NOT NULL REFERENCES delivery_order (id),
    driver_id        BIGINT        NOT NULL REFERENCES driver (id),
    method           VARCHAR(20)   NOT NULL CHECK (method IN ('MANUAL', 'AUTO')),
    assigned_by      BIGINT        REFERENCES app_user (id),
    score            DOUBLE PRECISION,
    eta_seconds      DOUBLE PRECISION,
    eta_score        DOUBLE PRECISION,
    workload_score   DOUBLE PRECISION,
    capacity_score   DOUBLE PRECISION,
    candidate_rank   INTEGER,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_assignment_order ON assignment (order_id);
CREATE INDEX idx_assignment_driver_created ON assignment (driver_id, created_at);

-- Scoring weights and limits, editable by ADMIN without a deploy (FR-14). Exactly one row.
CREATE TABLE assignment_config (
    id                    BIGINT PRIMARY KEY CHECK (id = 1),
    eta_weight            DOUBLE PRECISION NOT NULL CHECK (eta_weight >= 0),
    workload_weight       DOUBLE PRECISION NOT NULL CHECK (workload_weight >= 0),
    capacity_weight       DOUBLE PRECISION NOT NULL CHECK (capacity_weight >= 0),
    eta_cap_seconds       INTEGER          NOT NULL CHECK (eta_cap_seconds > 0),
    search_radius_meters  INTEGER          NOT NULL CHECK (search_radius_meters > 0),
    max_candidates        INTEGER          NOT NULL CHECK (max_candidates > 0),
    max_active_deliveries INTEGER          NOT NULL CHECK (max_active_deliveries > 0),
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version               BIGINT           NOT NULL DEFAULT 0,
    CHECK (eta_weight + workload_weight + capacity_weight > 0)
);

INSERT INTO assignment_config (id, eta_weight, workload_weight, capacity_weight, eta_cap_seconds,
                               search_radius_meters, max_candidates, max_active_deliveries)
VALUES (1, 0.6, 0.25, 0.15, 1800, 5000, 50, 8);
