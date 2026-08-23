package com.seatflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the application starts against real PostgreSQL and Redis, and that
 * Flyway brought the schema up to the state Hibernate validates against.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SeatflowApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Reaching here means Flyway migrated and ddl-auto=validate accepted the
        // entity mappings. Either failing would abort startup.
    }

    @Test
    void flywayAppliedTheBaselineMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void migrationSeededBothRoles() {
        var roles = jdbcTemplate.queryForList("SELECT name FROM roles ORDER BY name", String.class);
        assertThat(roles).containsExactly("ADMIN", "USER");
    }
}
