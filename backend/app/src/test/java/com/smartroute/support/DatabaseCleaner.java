package com.smartroute.support;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Empties all application tables between tests. Keeps Flyway's history table, and resets (rather than
 * empties) {@code assignment_config}, whose single row is created by a migration.
 */
@TestComponent
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'assignment_config')
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        // Same values as V4__assignment.sql.
        jdbc.update("""
                UPDATE assignment_config SET eta_weight = 0.6, workload_weight = 0.25, capacity_weight = 0.15,
                    eta_cap_seconds = 1800, search_radius_meters = 5000, max_candidates = 50, max_active_deliveries = 8
                WHERE id = 1""");
    }
}
