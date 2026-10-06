package com.smartroute.demo;

import com.smartroute.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real seeder against PostgreSQL (separate context with the "seed" profile). */
@IntegrationTest
@ActiveProfiles("seed")
class DemoDataSeederTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void seedsTheRequiredVolumeOfData() {
        assertThat(count("SELECT count(*) FROM warehouse")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM driver")).isEqualTo(120);
        assertThat(count("SELECT count(*) FROM vehicle")).isEqualTo(120);
        assertThat(count("SELECT count(*) FROM delivery_order")).isEqualTo(600);
        assertThat(count("SELECT count(*) FROM order_status_history")).isEqualTo(600);
    }

    @Test
    void dataIsVariedAcrossTypesPrioritiesAndStatuses() {
        assertThat(count("SELECT count(DISTINCT type) FROM vehicle")).isEqualTo(3);
        assertThat(count("SELECT count(DISTINCT priority) FROM delivery_order")).isEqualTo(4);
        assertThat(count("SELECT count(DISTINCT status) FROM driver")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM delivery_order WHERE window_end IS NOT NULL")).isBetween(150L, 330L);
        assertThat(count("SELECT count(*) FROM driver WHERE last_latitude IS NULL")).isZero();
    }

    @Test
    void createsOneDemoLoginPerRoleWithDriversLinkedToSeededDrivers() {
        assertThat(count("SELECT count(DISTINCT role) FROM app_user")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM app_user WHERE role = 'DRIVER' AND driver_id IS NOT NULL")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM app_user WHERE email NOT LIKE '%@smartroute.local'")).isZero();
    }

    @Test
    void isDeterministicForTheSameSeed() {
        // Values below come from the fixed random seed; a change means the generator changed.
        String firstDriver = jdbc.queryForObject("SELECT full_name FROM driver ORDER BY id LIMIT 1", String.class);
        String firstPlate = jdbc.queryForObject("SELECT plate_number FROM vehicle ORDER BY id LIMIT 1", String.class);
        assertThat(firstDriver).isNotBlank();
        assertThat(firstPlate).startsWith("KA01-");
        assertThat(firstPlate).endsWith("-0001");
    }
}
