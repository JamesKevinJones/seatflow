package com.seatflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Redis for integration tests.
 * <p>
 * Images are pinned to the same versions as {@code infra/docker-compose.dev.yml}.
 * This is not housekeeping: the reservation engine depends on PostgreSQL's
 * row-lock and predicate re-check behaviour, so testing against a different
 * version - or against H2 - would prove nothing about what actually ships.
 */
// Public so test classes in other packages can import it.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // PostgreSQLContainer is not generic in this Testcontainers version.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
