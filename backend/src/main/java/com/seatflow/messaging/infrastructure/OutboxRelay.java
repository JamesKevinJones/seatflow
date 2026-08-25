package com.seatflow.messaging.infrastructure;

import com.seatflow.messaging.domain.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed messages from the outbox table to Kafka.
 *
 * <p>This is the only component that talks to a broker, and it is deliberately
 * stupid: it reads rows, sends bytes to the topic named in the row, and marks
 * what the broker acknowledged. It never parses a payload and knows nothing
 * about bookings or seats. Adding a fourth event type requires no change here.
 *
 * <p><b>Delivery is at-least-once, and that is a decision rather than an
 * oversight.</b> Between a successful send and the {@code UPDATE} that marks the
 * row published there is a window in which this process can die. Reopening the
 * table afterwards, the row still says unpublished, so it is sent again.
 * Closing that window would need the send and the mark to be one atomic
 * operation across two systems, which is the very thing the outbox exists
 * because you cannot have. So the duplicate is accepted and pushed to the
 * consumers, which carry a {@code messageId} to recognise it - see
 * {@link com.seatflow.messaging.consumer.ProcessedMessages}.
 *
 * <p><b>Nothing here is on the correctness path.</b> Stop Kafka and reservations,
 * payments and bookings all continue: they only ever write a database row. The
 * backlog grows, the pending gauge climbs, and the relay drains it when the
 * broker returns. Losing the broker costs delivery latency, never a seat.
 */
@Component
@ConditionalOnProperty(name = "seatflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Distinct from the expiry sweeper's key. Two jobs sharing an advisory lock
     * key would silently block each other, and the symptom - one job simply not
     * running - looks nothing like a lock collision.
     */
    private static final long RETENTION_LOCK_KEY = 0x0117B0C1L;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;

    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;

    private final Counter published;
    private final Counter failures;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            MeterRegistry registry,
            @Value("${seatflow.outbox.batch-size:128}") int batchSize,
            @Value("${seatflow.outbox.send-timeout-ms:10000}") long sendTimeoutMillis,
            @Value("${seatflow.outbox.retention-hours:168}") long retentionHours) {

        this.repository = repository;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMillis);
        this.retention = Duration.ofHours(retentionHours);

        this.published = Counter.builder("seatflow.outbox.published")
                .description("Messages the broker acknowledged")
                .register(registry);

        // Rises when Kafka is unreachable or rejecting. Paired with the backlog
        // gauge this distinguishes "nothing to send" from "cannot send".
        this.failures = Counter.builder("seatflow.outbox.failures")
                .description("Send attempts the broker did not acknowledge")
                .register(registry);
    }

    /**
     * One batch per tick.
     *
     * <p>Transactional, and the transaction spans the Kafka round trip - the row
     * locks taken by {@code FOR UPDATE SKIP LOCKED} have to still be held when
     * the rows are marked, or a second relay could claim and resend them. The
     * cost is bounded: at most {@code batchSize} rows, for at most
     * {@code sendTimeout}, and other instances skip past locked rows rather than
     * queueing behind them.
     */
    @Scheduled(
            fixedDelayString = "${seatflow.outbox.poll-interval-ms:500}",
            initialDelayString = "${seatflow.outbox.initial-delay-ms:5000}")
    @Transactional
    public void drain() {
        List<OutboxMessage> batch = repository.claimUnpublished(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        List<CompletableFuture<?>> sends = new ArrayList<>(batch.size());
        for (OutboxMessage message : batch) {
            try {
                sends.add(kafka.send(message.getTopic(), message.getPartitionKey(), message.getPayload()));
            } catch (RuntimeException e) {
                // send() can fail before it ever returns a future - an unreachable
                // broker blocks fetching metadata and then throws. A completed
                // exceptional future keeps the two failure paths in one place.
                sends.add(CompletableFuture.failedFuture(e));
            }
        }

        awaitQuietly(sends);
        settle(batch, sends);
    }

    /**
     * Waits for the whole batch, then stops waiting.
     * <p>
     * The outcome of each individual send is read afterwards, so there is
     * nothing to do with an exception here: a batch where one send failed and a
     * batch that ran out of time are classified the same way, message by
     * message.
     */
    private void awaitQuietly(List<CompletableFuture<?>> sends) {
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Classified below, per message.
        }
    }

    /**
     * Marks each message by what actually happened to it.
     *
     * <p>Per message rather than per batch, because "the batch timed out" is not
     * the same as "these messages were not delivered" - some of them usually
     * were, and resending those is a duplicate that a consumer then has to
     * discard. Being precise here keeps the number of duplicates near zero even
     * though the design tolerates them.
     */
    private void settle(List<OutboxMessage> batch, List<CompletableFuture<?>> sends) {
        List<Long> acknowledged = new ArrayList<>();
        List<Long> unacknowledged = new ArrayList<>();
        String firstError = null;

        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<?> send = sends.get(i);
            Long id = batch.get(i).getId();

            if (send.isDone() && !send.isCompletedExceptionally()) {
                acknowledged.add(id);
                continue;
            }

            unacknowledged.add(id);
            if (firstError == null) {
                firstError = describeFailure(send);
            }
        }

        if (!acknowledged.isEmpty()) {
            repository.markPublished(acknowledged);
            published.increment(acknowledged.size());
            log.debug("Relayed {} message(s) to Kafka", acknowledged.size());
        }

        if (!unacknowledged.isEmpty()) {
            repository.markFailed(unacknowledged, truncate(firstError));
            failures.increment(unacknowledged.size());
            // WARN, not ERROR: the messages are safe in the table and will go
            // out when the broker comes back. Nothing has been lost.
            log.warn("{} outbox message(s) not acknowledged, left for retry: {}",
                    unacknowledged.size(), firstError);
        }
    }

    private static String describeFailure(CompletableFuture<?> send) {
        if (!send.isDone()) {
            return "timed out waiting for the broker";
        }
        try {
            send.getNow(null);
            return "unknown";
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    /** The column is unbounded TEXT, but a stack trace in a table row helps nobody. */
    private static String truncate(String error) {
        if (error == null) {
            return "unknown";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    /**
     * Deletes published rows once they stop being useful evidence.
     *
     * <p>Behind an advisory lock, unlike {@link #drain()}. A DELETE has no
     * equivalent of SKIP LOCKED partitioning, so several instances running it at
     * once would just contend over the same rows to do identical work.
     */
    @Scheduled(
            fixedDelayString = "${seatflow.outbox.retention-sweep-ms:3600000}",
            initialDelayString = "${seatflow.outbox.retention-initial-delay-ms:60000}")
    @Transactional
    public void sweepPublished() {
        if (!repository.tryAdvisoryLock(RETENTION_LOCK_KEY)) {
            log.trace("Outbox retention sweep skipped: another instance holds the lock");
            return;
        }

        int deleted = repository.deletePublishedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Outbox retention: removed {} published message(s) older than {}", deleted, retention);
        }
    }
}
