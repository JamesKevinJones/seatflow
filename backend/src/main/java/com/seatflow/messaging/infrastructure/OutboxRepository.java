package com.seatflow.messaging.infrastructure;

import com.seatflow.messaging.domain.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Takes the oldest unpublished messages and locks them for this transaction.
     *
     * <p><b>{@code SKIP LOCKED} is what makes the relay safe to run on every
     * instance at once.</b> Without it, two relays would block on the same rows
     * and take turns; with it, each one takes a disjoint batch and they drain
     * the backlog in parallel. No leader election, no advisory lock, no
     * designated instance - PostgreSQL hands out disjoint work by construction.
     *
     * <p>{@code ORDER BY id} rather than by {@code created_at}: the sequence is
     * the insertion order and timestamps from different instances can disagree
     * by however far their clocks have drifted.
     *
     * <p>The locks are held until the transaction commits, which includes the
     * time spent talking to Kafka. That is the accepted cost of not needing a
     * two-phase claim, and it is bounded by the batch size and the send timeout.
     */
    @Query(value = """
            SELECT * FROM outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimUnpublished(@Param("batchSize") int batchSize);

    /** Called only once the broker has acknowledged every one of these. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET published_at = now(),
                   attempts = attempts + 1,
                   last_error = NULL
             WHERE id IN (:ids)
            """, nativeQuery = true)
    int markPublished(@Param("ids") Collection<Long> ids);

    /**
     * Records a failed attempt and leaves the row unpublished, so the next tick
     * picks it up again. Nothing is ever dropped: a message that cannot be sent
     * stays in the table and stays visible in the pending gauge.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox
               SET attempts = attempts + 1,
                   last_error = :error
             WHERE id IN (:ids)
            """, nativeQuery = true)
    int markFailed(@Param("ids") Collection<Long> ids, @Param("error") String error);

    /** Backs the pending-backlog gauge. A number that only grows means the relay is stuck. */
    @Query(value = "SELECT count(*) FROM outbox WHERE published_at IS NULL", nativeQuery = true)
    long countPending();

    /**
     * Retention. Published rows are evidence for as long as they are useful for
     * investigating a bad deployment, and dead weight afterwards.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < :before",
            nativeQuery = true)
    int deletePublishedBefore(@Param("before") Instant before);

    /**
     * Same non-blocking advisory lock the expiry sweeper uses, with a different
     * key. Only the retention sweep needs it - draining does not, because
     * SKIP LOCKED already partitions that work.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryLock(@Param("key") long key);
}
