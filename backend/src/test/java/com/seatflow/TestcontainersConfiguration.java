package com.seatflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL, Redis and Kafka for integration tests.
 * <p>
 * Images are pinned to the same versions as {@code infra/docker-compose.dev.yml}.
 * This is not housekeeping: the reservation engine depends on PostgreSQL's
 * row-lock and predicate re-check behaviour, so testing against a different
 * version - or against H2 - would prove nothing about what actually ships.
 * <p>
 * The same argument rules out {@code @EmbeddedKafka} for the outbox tests, which
 * is why {@code spring-boot-starter-kafka-test} is not a dependency. The relay's
 * behaviour under a broker that is slow, or absent, or acknowledging late is the
 * interesting part, and an in-JVM broker sharing the test's own lifecycle
 * reproduces none of it.
 * <p>
 * All three containers are declared here rather than per test class so that
 * every {@code @SpringBootTest} shares one Spring context - and therefore one
 * set of containers - instead of starting its own.
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

    /**
     * KRaft mode, so there is no ZooKeeper to start. Boot recognises this
     * container type and points {@code spring.kafka.bootstrap-servers} at it.
     */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }
}
