-- Phase 13. Two indexes, each added because a measurement asked for it and not before.
--
-- The analytics endpoints all ask the same shape of question: what happened between two timestamps. Without
-- an index on the timestamp, every one of them reads the whole history table, and the history table is the
-- one that grows fastest in this schema (about six rows per order). Measured with 100k orders and 405k
-- history rows (scripts/perf/db_queries.py --scale 100000, a seven-day window):
--
--   query                       before    after
--   analytics_status_counts     34.5 ms   1.3 ms
--   analytics_throughput        45.4 ms   0.8 ms
--   analytics_duration          41.8 ms   6.0 ms
--   analytics_punctuality       41.1 ms  11.5 ms
--   analytics_fleet             45.1 ms  20.1 ms
--   orders_page_count           25.7 ms  15.2 ms
--
-- The cost is two more indexes to maintain on inserts. Both tables are append-only in normal operation
-- (a status change inserts a history row; an order is inserted once), so the write path pays one index
-- entry, which is the cheaper side of this trade.

-- Every analytics query filters on the status that was reached and when it was reached. Leading with
-- to_status is deliberate: it is the equality column, and the timestamp is the range after it, which is the
-- order that lets a single index scan serve 'DELIVERED between A and B'.
CREATE INDEX idx_history_status_changed ON order_status_history (to_status, changed_at);

-- The overview's counts-by-status and the order list's page count both filter orders by creation time.
-- idx_order_status_created cannot serve them: it leads with status, so a query that groups by status has to
-- read all of it.
CREATE INDEX idx_order_created ON delivery_order (created_at);
