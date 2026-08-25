package com.seatflow.messaging.infrastructure;

import com.seatflow.messaging.contract.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the three topics so a fresh environment comes up complete.
 *
 * <p>Spring creates these through {@code KafkaAdmin} at startup if they are
 * absent, and leaves them alone if they exist - it never shrinks partitions or
 * rewrites config on an existing topic. Relying on the broker's
 * {@code auto.create.topics.enable} instead would work right up until a typo in
 * a topic name silently created a fourth topic that nothing consumes.
 *
 * <p><b>An unreachable broker must not stop the application.</b> Boot's default
 * {@code KafkaAdmin} is non-fatal, which is what makes that true, and it needs
 * to stay that way: seats are sold from PostgreSQL, and a ticket platform that
 * refuses to boot because a message broker is down has put the broker on the
 * correctness path by accident.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(Topics.BOOKING_CONFIRMED)
                .partitions(Topics.PARTITIONS)
                .replicas(Topics.REPLICATION_FACTOR)
                .build();
    }

    @Bean
    NewTopic reservationExpiredTopic() {
        return TopicBuilder.name(Topics.RESERVATION_EXPIRED)
                .partitions(Topics.PARTITIONS)
                .replicas(Topics.REPLICATION_FACTOR)
                .build();
    }

    @Bean
    NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(Topics.PAYMENT_COMPLETED)
                .partitions(Topics.PARTITIONS)
                .replicas(Topics.REPLICATION_FACTOR)
                .build();
    }
}
