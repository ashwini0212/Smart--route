package com.smartroute.analytics;

import com.smartroute.analytics.AnalyticsResponses.DriverPerformance;
import com.smartroute.analytics.AnalyticsResponses.Duration;
import com.smartroute.analytics.AnalyticsResponses.EtaAccuracy;
import com.smartroute.analytics.AnalyticsResponses.FleetUsage;
import com.smartroute.analytics.AnalyticsResponses.Overview;
import com.smartroute.analytics.AnalyticsResponses.ThroughputDay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The analytics queries (FR-23).
 *
 * <p>Written as SQL against the tables the system already writes, rather than against a separate read model:
 * at this size the aggregates run in milliseconds on indexed columns, and a read model would be a second copy
 * of the truth to keep in step for no gain. If these ever get slow the honest fix is a materialized view
 * refreshed from the event stream, not an index on everything.
 *
 * <p>Every number here is defined by its query, and every response carries those definitions in words. The
 * defensible ones are called out where they are computed: "on time" means delivered before the window end
 * <em>among orders that had a window</em>, and "utilization" is a share of the fleet that worked, not a share
 * of anyone's time — nothing in this system knows how long a driver was on shift.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    /** The statuses that mean a delivery is under way, as the order module defines them. */
    private static final String ACTIVE_STATUSES = "('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT')";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AnalyticsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Overview overview(int days) {
        Instant to = clock.instant();
        Instant from = to.minus(java.time.Duration.ofDays(days));
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) AS n FROM delivery_order WHERE created_at >= ? AND created_at <= ?"
                        + " GROUP BY status ORDER BY status",
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("n"));
                }, fromTs, toTs);
        long created = byStatus.values().stream().mapToLong(Long::longValue).sum();

        // Delivered/failed/cancelled are counted by *when the status changed*, not by when the order was
        // created: an order created last week and delivered today belongs in today's delivered count.
        long delivered = countChanges("DELIVERED", fromTs, toTs);
        long failed = countChanges("FAILED", fromTs, toTs);
        long cancelled = countChanges("CANCELLED", fromTs, toTs);

        long waitingNow = jdbc.queryForObject("SELECT count(*) FROM delivery_order WHERE status = 'CREATED'", Long.class);
        long activeNow = jdbc.queryForObject(
                "SELECT count(*) FROM delivery_order WHERE status IN " + ACTIVE_STATUSES, Long.class);

        // On time: delivered before the promised end, among the deliveries that had a promise at all.
        List<long[]> punctuality = jdbc.query("""
                SELECT count(*) FILTER (WHERE o.window_end IS NOT NULL)                             AS with_window,
                       count(*) FILTER (WHERE o.window_end IS NOT NULL AND h.changed_at <= o.window_end) AS on_time
                FROM order_status_history h
                JOIN delivery_order o ON o.id = h.order_id
                WHERE h.to_status = 'DELIVERED' AND h.changed_at >= ? AND h.changed_at <= ?
                """, (rs, row) -> new long[]{rs.getLong("with_window"), rs.getLong("on_time")}, fromTs, toTs);
        long withWindow = punctuality.isEmpty() ? 0 : punctuality.get(0)[0];
        long onTime = punctuality.isEmpty() ? 0 : punctuality.get(0)[1];
        Double onTimeRate = withWindow == 0 ? null : round((double) onTime / withWindow, 4);

        Duration duration = durationMinutes("""
                SELECT EXTRACT(EPOCH FROM (d.changed_at - a.changed_at)) / 60 AS minutes
                FROM order_status_history d
                JOIN LATERAL (
                    SELECT changed_at FROM order_status_history
                    WHERE order_id = d.order_id AND to_status = 'ASSIGNED'
                    ORDER BY changed_at DESC LIMIT 1
                ) a ON TRUE
                WHERE d.to_status = 'DELIVERED' AND d.changed_at >= ? AND d.changed_at <= ?
                """, fromTs, toTs);

        return new Overview(from, to, days, byStatus, created, delivered, failed, cancelled, waitingNow, activeNow,
                withWindow, onTime, withWindow - onTime, onTimeRate, duration,
                List.of("Counts by status are the orders created in the window, grouped by the status they are in now.",
                        "Delivered, failed and cancelled are counted by when the status changed, so they can include orders created earlier.",
                        "On time means delivered at or before the order's window end, counted only over deliveries that had a window.",
                        "Assignment to delivery is measured from the last ASSIGNED change to the DELIVERED change, so it includes"
                                + " everything the driver did in between, not just this delivery."));
    }

    public List<ThroughputDay> throughput(int days) {
        Instant to = clock.instant();
        Instant from = to.minus(java.time.Duration.ofDays(days));
        Map<LocalDate, long[]> rows = new LinkedHashMap<>();
        LocalDate firstDay = LocalDate.ofInstant(from, java.time.ZoneOffset.UTC);
        for (int offset = 0; offset <= days; offset++) {
            rows.put(firstDay.plusDays(offset), new long[4]);
        }
        jdbc.query("SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n FROM delivery_order"
                        + " WHERE created_at >= ? GROUP BY 1",
                rs -> {
                    long[] counts = rows.get(rs.getObject("day", LocalDate.class));
                    if (counts != null) counts[0] = rs.getLong("n");
                }, Timestamp.from(from));
        jdbc.query("SELECT (changed_at AT TIME ZONE 'UTC')::date AS day, to_status, count(*) AS n"
                        + " FROM order_status_history WHERE changed_at >= ?"
                        + " AND to_status IN ('DELIVERED', 'FAILED', 'CANCELLED') GROUP BY 1, 2",
                rs -> {
                    long[] counts = rows.get(rs.getObject("day", LocalDate.class));
                    if (counts == null) return;
                    int index = switch (rs.getString("to_status")) {
                        case "DELIVERED" -> 1;
                        case "FAILED" -> 2;
                        default -> 3;
                    };
                    counts[index] = rs.getLong("n");
                }, Timestamp.from(from));

        List<ThroughputDay> result = new ArrayList<>();
        rows.forEach((day, counts) -> result.add(new ThroughputDay(day, counts[0], counts[1], counts[2], counts[3])));
        return result;
    }

    public FleetUsage fleetUsage(int days, int limit) {
        Instant to = clock.instant();
        Timestamp fromTs = Timestamp.from(to.minus(java.time.Duration.ofDays(days)));
        Timestamp toTs = Timestamp.from(to);

        // One row per delivery, attributed to the driver who held the order when it ended. An order can have
        // several assignment rows (it was reassigned, or auto-dispatch retried it), so joining the assignment
        // table directly counts a delivery once per assignment — which is how this query first reported 24
        // deliveries for a driver on a day the whole fleet delivered 45.
        List<DriverPerformance> perDriver = jdbc.query("""
                WITH completions AS (
                    SELECT h.order_id,
                           h.to_status,
                           h.changed_at,
                           o.window_end,
                           o.driver_id,
                           (SELECT a.created_at FROM assignment a
                            WHERE a.order_id = h.order_id AND a.created_at <= h.changed_at
                            ORDER BY a.created_at DESC, a.id DESC LIMIT 1) AS assigned_at
                    FROM order_status_history h
                    JOIN delivery_order o ON o.id = h.order_id
                    WHERE h.to_status IN ('DELIVERED', 'FAILED') AND h.changed_at >= ? AND h.changed_at <= ?
                      AND o.driver_id IS NOT NULL
                )
                SELECT d.id, d.code, d.status, d.active_delivery_count,
                       count(*) FILTER (WHERE c.to_status = 'DELIVERED')                                   AS delivered,
                       count(*) FILTER (WHERE c.to_status = 'DELIVERED' AND c.window_end IS NOT NULL
                                              AND c.changed_at > c.window_end)                             AS late,
                       count(*) FILTER (WHERE c.to_status = 'FAILED')                                      AS failed,
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY EXTRACT(EPOCH FROM (c.changed_at - c.assigned_at)) / 60)               AS median_minutes
                FROM completions c
                JOIN driver d ON d.id = c.driver_id
                GROUP BY d.id, d.code, d.status, d.active_delivery_count
                ORDER BY delivered DESC, d.code
                LIMIT ?
                """, (rs, row) -> new DriverPerformance(rs.getLong("id"), rs.getString("code"), rs.getString("status"),
                        rs.getLong("delivered"), rs.getLong("late"), rs.getLong("failed"),
                        rs.getInt("active_delivery_count"), round(nullableDouble(rs.getObject("median_minutes")), 1)),
                fromTs, toTs, limit);

        int drivers = jdbc.queryForObject("SELECT count(*) FROM driver", Integer.class);
        Integer worked = jdbc.queryForObject("""
                SELECT count(DISTINCT o.driver_id)
                FROM order_status_history h
                JOIN delivery_order o ON o.id = h.order_id
                WHERE h.to_status = 'DELIVERED' AND h.changed_at >= ? AND h.changed_at <= ? AND o.driver_id IS NOT NULL
                """, Integer.class, fromTs, toTs);
        Long deliveries = jdbc.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE to_status = 'DELIVERED' AND changed_at >= ? AND changed_at <= ?",
                Long.class, fromTs, toTs);

        return new FleetUsage(drivers, worked,
                drivers == 0 ? null : round((double) worked / drivers, 4),
                worked == 0 ? null : round((double) deliveries / worked, 2),
                perDriver,
                List.of("A driver counts as having worked if they completed at least one delivery in the window.",
                        "Share of fleet used is driversWithDeliveries / driverCount. It is not a share of anyone's time:"
                                + " this system does not record shifts, so true utilization cannot be computed from it.",
                        "Late counts deliveries completed after the order's window end; orders without a window are never late.",
                        "A delivery is counted once, against the driver recorded on the order — not once per assignment"
                                + " the order collected on its way there.",
                        "Median minutes runs from the assignment that was in force at the time to the DELIVERED status change."));
    }

    public EtaAccuracy etaAccuracy(int days) {
        Instant to = clock.instant();
        Timestamp fromTs = Timestamp.from(to.minus(java.time.Duration.ofDays(days)));
        Timestamp toTs = Timestamp.from(to);

        // Predicted: the ETA stored with the assignment — travel time from the driver's position to the pickup.
        // Actual: assignment to PICKED_UP, which includes anything else the driver was doing. The difference is
        // therefore an upper bound on the routing error, and the response says so.
        List<EtaAccuracy> rows = jdbc.query("""
                WITH pairs AS (
                    -- One row per pickup, paired with the assignment that was in force when it happened. An
                    -- order can carry several assignment rows, so the assignment is chosen, not joined. The
                    -- choice is a LATERAL rather than a subquery returning an id and a join back to the
                    -- table: the join back made the planner hash all of `assignment` for what is one index
                    -- lookup per pickup (51 ms against 12 ms at 100k orders, see V7__analytics_indexes.sql).
                    SELECT a.eta_seconds / 60.0                                      AS predicted,
                           EXTRACT(EPOCH FROM (h.changed_at - a.created_at)) / 60    AS actual
                    FROM order_status_history h
                    JOIN LATERAL (
                        SELECT a.eta_seconds, a.created_at FROM assignment a
                        WHERE a.order_id = h.order_id AND a.created_at <= h.changed_at
                          AND a.eta_seconds IS NOT NULL
                        ORDER BY a.created_at DESC, a.id DESC LIMIT 1
                    ) a ON TRUE
                    WHERE h.to_status = 'PICKED_UP' AND h.changed_at >= ? AND h.changed_at <= ?
                )
                SELECT count(*)                                                               AS samples,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY predicted)                 AS predicted_median,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY actual)                    AS actual_median,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY actual - predicted)        AS median_difference,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY actual - predicted)        AS p90_difference,
                       count(*) FILTER (WHERE abs(actual - predicted) <= 5)                   AS within_five
                FROM pairs
                """, (rs, row) -> new EtaAccuracy(rs.getLong("samples"),
                        round(nullableDouble(rs.getObject("predicted_median")), 2),
                        round(nullableDouble(rs.getObject("actual_median")), 2),
                        round(nullableDouble(rs.getObject("median_difference")), 2),
                        round(nullableDouble(rs.getObject("p90_difference")), 2),
                        rs.getLong("within_five"), List.of()), fromTs, toTs);

        EtaAccuracy result = rows.get(0);
        return new EtaAccuracy(result.samples(), result.predictedMedianMinutes(), result.actualMedianMinutes(),
                result.medianDifferenceMinutes(), result.p90DifferenceMinutes(), result.withinFiveMinutes(),
                List.of("Predicted is the ETA recorded when the order was assigned: travel time from the driver's position"
                                + " to the pickup warehouse on the road network, computed by Dijkstra.",
                        "Actual is the time between that assignment and the driver reporting PICKED_UP. Pickups are"
                                + " counted in the window they happened in, each paired with one assignment.",
                        "Actual therefore includes finishing earlier deliveries, loading and any wait, so the difference is an"
                                + " upper bound on the routing error, not a measurement of it.",
                        "With simulated drivers the pickup is reported by the simulator, so these numbers describe the"
                                + " simulation, not real driving."));
    }

    private long countChanges(String status, Timestamp from, Timestamp to) {
        return jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE to_status = ?"
                + " AND changed_at >= ? AND changed_at <= ?", Long.class, status, from, to);
    }

    private Duration durationMinutes(String sql, Timestamp from, Timestamp to) {
        return jdbc.query(sql, rs -> {
            List<Double> minutes = new ArrayList<>();
            while (rs.next()) {
                minutes.add(rs.getDouble("minutes"));
            }
            if (minutes.isEmpty()) {
                return new Duration(0, null, null, null);
            }
            minutes.sort(Double::compareTo);
            double mean = minutes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return new Duration(minutes.size(), round(percentile(minutes, 50), 1), round(percentile(minutes, 90), 1),
                    round(mean, 1));
        }, from, to);
    }

    private static double percentile(List<Double> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Double round(Double value, int places) {
        if (value == null) {
            return null;
        }
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
