-- SmartRoute core domain: warehouses, fleet (vehicles, drivers) and delivery orders.
-- Conventions:
--   * BIGINT identity primary keys (compact, fast joins; public codes like ORD-000123 are separate).
--   * created_at/updated_at on every table; "version" for optimistic locking where rows are updated concurrently.
--   * Enums are stored as text with CHECK constraints: readable in SQL and safe to extend with a migration.
--   * Money/weights use NUMERIC to avoid binary floating-point rounding in sums.

CREATE TABLE warehouse (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    address     VARCHAR(255) NOT NULL,
    latitude    DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude   DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE vehicle (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plate_number   VARCHAR(20)   NOT NULL UNIQUE,
    type           VARCHAR(20)   NOT NULL CHECK (type IN ('BIKE', 'VAN', 'TRUCK')),
    max_weight_kg  NUMERIC(10,2) NOT NULL CHECK (max_weight_kg > 0),
    max_volume_m3  NUMERIC(10,3) NOT NULL CHECK (max_volume_m3 > 0),
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE driver (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   VARCHAR(20)   NOT NULL UNIQUE,
    full_name              VARCHAR(120)  NOT NULL,
    phone                  VARCHAR(20)   NOT NULL,
    -- One vehicle can be assigned to at most one driver at a time.
    vehicle_id             BIGINT        UNIQUE REFERENCES vehicle (id),
    home_warehouse_id      BIGINT        NOT NULL REFERENCES warehouse (id),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE'
                               CHECK (status IN ('OFFLINE', 'AVAILABLE', 'ON_DELIVERY', 'ON_BREAK')),
    current_load_kg        NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (current_load_kg >= 0),
    current_load_m3        NUMERIC(10,3) NOT NULL DEFAULT 0 CHECK (current_load_m3 >= 0),
    active_delivery_count  INTEGER       NOT NULL DEFAULT 0 CHECK (active_delivery_count >= 0),
    last_latitude          DOUBLE PRECISION CHECK (last_latitude BETWEEN -90 AND 90),
    last_longitude         DOUBLE PRECISION CHECK (last_longitude BETWEEN -180 AND 180),
    last_location_at       TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0,
    CHECK ((last_latitude IS NULL) = (last_longitude IS NULL))
);

-- Assignment looks up AVAILABLE drivers constantly; most drivers are not available at any moment,
-- so a partial index stays small and only covers the rows that query needs.
CREATE INDEX idx_driver_available ON driver (home_warehouse_id) WHERE status = 'AVAILABLE';

CREATE TABLE delivery_order (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   VARCHAR(20)   NOT NULL UNIQUE,
    warehouse_id           BIGINT        NOT NULL REFERENCES warehouse (id),
    customer_name          VARCHAR(120)  NOT NULL,
    drop_address           VARCHAR(255)  NOT NULL,
    drop_latitude          DOUBLE PRECISION NOT NULL CHECK (drop_latitude BETWEEN -90 AND 90),
    drop_longitude         DOUBLE PRECISION NOT NULL CHECK (drop_longitude BETWEEN -180 AND 180),
    priority               VARCHAR(10)   NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'CREATED'
                               CHECK (status IN ('CREATED', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT',
                                                 'DELIVERED', 'FAILED', 'CANCELLED')),
    weight_kg              NUMERIC(10,2) NOT NULL CHECK (weight_kg > 0),
    volume_m3              NUMERIC(10,3) NOT NULL CHECK (volume_m3 > 0),
    required_vehicle_type  VARCHAR(20)   CHECK (required_vehicle_type IN ('BIKE', 'VAN', 'TRUCK')),
    window_start           TIMESTAMPTZ,
    window_end             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0,
    CHECK (window_start IS NULL OR window_end IS NULL OR window_start < window_end)
);

-- Dispatch queue: "unassigned orders, most urgent first". Status first because every query filters on it.
CREATE INDEX idx_order_status_created ON delivery_order (status, created_at);
-- Delay detection only scans orders that are still moving.
CREATE INDEX idx_order_active_window ON delivery_order (window_end)
    WHERE status IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT');
CREATE INDEX idx_order_warehouse ON delivery_order (warehouse_id);

-- Every status change is recorded: delivery history, audit, and analytics (time spent in each state).
CREATE TABLE order_status_history (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES delivery_order (id) ON DELETE CASCADE,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    reason       VARCHAR(255),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, changed_at);

-- Human-readable codes (DRV-000001, ORD-000001) come from sequences: unique and gap-tolerant without locking.
CREATE SEQUENCE driver_code_seq;
CREATE SEQUENCE order_code_seq;
