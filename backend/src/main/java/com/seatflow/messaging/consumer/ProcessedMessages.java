package com.seatflow.messaging.consumer;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which messages have already been handled, so at-least-once delivery
 * does not become at-least-once <i>effect</i>.
 *
 * <p>Delivery guarantees are about the pipe; idempotence is about the consumer.
 * The relay will occasionally send the same message twice, so anything with a
 * side effect that is not naturally repeatable - adding to a revenue total,
 * sending a confirmation - has to recognise a message it has already seen. That
 * recognition is what {@code messageId} exists for.
 *
 * <p><b>What this is not.</b> It is an in-memory, per-instance, bounded set. It
 * catches the duplicates that actually occur, which arrive seconds apart from a
 * relay that restarted mid-batch. It does not survive a restart, is not shared
 * between instances, and forgets the oldest entries once it is full. A system
 * where double-counting costs real money writes the message id to a table inside
 * the consumer's own transaction, so that "recorded the effect" and "recorded
 * having seen it" commit together - the same argument as the outbox, pointed the
 * other way. That is the correct implementation and this is the honest
 * approximation of it.
 *
 * <p>Sizing note: {@link #CAPACITY} only has to cover the redelivery window, not
 * all history. Ten thousand is far more than a relay batch.
 */
@Component
public class ProcessedMessages {

    private static final int CAPACITY = 10_000;

    /**
     * Access-ordered so a message seen again stays hot rather than ageing out
     * while it is still being redelivered.
     */
    private final Set<UUID> seen = Collections.newSetFromMap(
            new LinkedHashMap<UUID, Boolean>(CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > CAPACITY;
                }
            });

    /**
     * @return {@code true} the first time a message id is offered, {@code false}
     *         for any repeat. Callers act only on {@code true}.
     */
    public synchronized boolean firstSighting(UUID messageId) {
        return seen.add(messageId);
    }

    synchronized int size() {
        return seen.size();
    }
}
