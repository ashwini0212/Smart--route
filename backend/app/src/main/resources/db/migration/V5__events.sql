-- Phase 9: domain events.

-- Transactional outbox. An event row is written in the same transaction as the business change, so the
-- two can never disagree (a Kafka send inside a transaction could succeed while the transaction rolls back).
-- A relay then publishes unsent rows and stamps published_at. Delivery is therefore at-least-once:
-- a crash between send and stamp re-sends, which is why consumers deduplicate on event_id.
CREATE TABLE outbox_event (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(60)  NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    event_version  INTEGER      NOT NULL CHECK (event_version > 0),
    topic          VARCHAR(80)  NOT NULL,
    partition_key  VARCHAR(60)  NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);

-- The relay's only query: oldest unpublished rows first. Partial, so published rows (the vast majority
-- over time) never enter the index.
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- Consumer-side idempotency: one row per (consumer group, event). The primary key makes a second delivery
-- of the same event fail to insert, which the consumer treats as "already handled, skip".
CREATE TABLE processed_event (
    consumer_group VARCHAR(60) NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- What the event-log consumer records: the readable stream behind the System Events page.
CREATE TABLE system_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    event_type     VARCHAR(60)  NOT NULL,
    event_version  INTEGER      NOT NULL,
    topic          VARCHAR(80)  NOT NULL,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(60)  NOT NULL,
    summary        VARCHAR(300) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_system_event_occurred ON system_event (occurred_at DESC, id DESC);
CREATE INDEX idx_system_event_type ON system_event (event_type, occurred_at DESC);
CREATE INDEX idx_system_event_aggregate ON system_event (aggregate_type, aggregate_id, occurred_at DESC);
