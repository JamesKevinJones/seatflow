package com.seatflow.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One message waiting to reach Kafka, or the record that it already did.
 *
 * <p>Written in the same transaction as the change it describes. That is the
 * entire point of the pattern and the reason this is an ordinary JPA entity
 * rather than anything clever: it commits with the booking, or it does not exist.
 *
 * <p>The payload is stored already serialized. Doing it at record time rather
 * than at send time means the relay never has to know what any of these messages
 * mean - it moves bytes to a topic and marks a row.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    /**
     * IDENTITY, so the value comes from the BIGSERIAL sequence on insert. It
     * must not be assigned here: an entity that arrives with an id already set
     * makes Spring Data issue a merge, and a merge on a row that does not exist
     * yet is an UPDATE that silently affects nothing.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "message_type", nullable = false, updatable = false)
    private String messageType;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, updatable = false)
    private String partitionKey;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** NULL until the relay has a broker acknowledgement in hand. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxMessage() {
        // JPA.
    }

    public static OutboxMessage pending(
            UUID messageId,
            String messageType,
            String aggregateType,
            UUID aggregateId,
            String topic,
            String partitionKey,
            String payload) {

        OutboxMessage message = new OutboxMessage();
        message.messageId = messageId;
        message.messageType = messageType;
        message.aggregateType = aggregateType;
        message.aggregateId = aggregateId;
        message.topic = topic;
        message.partitionKey = partitionKey;
        message.payload = payload;
        message.createdAt = Instant.now();
        message.attempts = 0;
        return message;
    }

    public Long getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    @Override
    public String toString() {
        return "OutboxMessage[id=%d, type=%s, topic=%s, published=%s]"
                .formatted(id, messageType, topic, publishedAt != null);
    }
}
