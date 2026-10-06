-- Phase 6: history of computed routes (FR-11). The road graph itself lives in files and memory (ED-14).

CREATE TABLE route_record (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requested_by    BIGINT           NOT NULL REFERENCES app_user (id),
    mode            VARCHAR(10)      NOT NULL CHECK (mode IN ('SHORTEST', 'FASTEST')),
    from_latitude   DOUBLE PRECISION NOT NULL,
    from_longitude  DOUBLE PRECISION NOT NULL,
    to_latitude     DOUBLE PRECISION NOT NULL,
    to_longitude    DOUBLE PRECISION NOT NULL,
    from_node       INTEGER          NOT NULL,
    to_node         INTEGER          NOT NULL,
    distance_m      DOUBLE PRECISION NOT NULL CHECK (distance_m >= 0),
    duration_s      DOUBLE PRECISION NOT NULL CHECK (duration_s >= 0),
    graph_version   BIGINT           NOT NULL,
    algorithm       VARCHAR(60)      NOT NULL,
    nodes_settled   INTEGER          NOT NULL,
    cache_hit       BOOLEAN          NOT NULL,
    -- Flattened [lat0, lon0, lat1, lon1, ...]; a native array keeps one route in one row.
    path            DOUBLE PRECISION[] NOT NULL,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version         BIGINT           NOT NULL DEFAULT 0
);

-- "My recent routes", newest first.
CREATE INDEX idx_route_record_user_created ON route_record (requested_by, created_at DESC);
