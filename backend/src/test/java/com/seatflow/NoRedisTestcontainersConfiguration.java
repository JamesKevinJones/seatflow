package com.seatflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka, and deliberately <b>no Redis</b>.
 *
 * <p>Used by {@code RedisUnavailableIT} to prove the application starts and
 * sells seats when the cache tier is unreachable. It cannot share
 * {@link TestcontainersConfiguration}, because a {@code @ServiceConnection}
 * Redis container overrides the host and port properties - which is exactly the
 * behaviour that makes that class useful everywhere else, and exactly what has
 * to be absent here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NoRedisTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }
}
