package com.seatflow.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact that has already happened, published for anyone who cares.
 *
 * <p>These records are a <b>published contract</b>, not internal types. Once a
 * consumer outside this repository reads one, its field names are an API:
 * adding a field is safe, renaming or removing one is a breaking change. That is
 * the reason they live in their own package with no dependency on any module's
 * entities - a JPA entity serialized onto a topic leaks lazy-loading proxies,
 * database column names, and the freedom to refactor.
 *
 * <p>Sealed on purpose. Routing a message to a topic is an exhaustive
 * {@code switch} in {@link com.seatflow.messaging.application.OutboxRecorder},
 * so adding a fourth event without deciding where it goes will not compile.
 *
 * <p><b>A note on the word "event".</b> In this codebase an <i>event</i> is a
 * concert with seats. To keep that meaning intact, the identity of a message is
 * {@link #messageId()} and never "eventId", and {@code eventId} inside these
 * records always means the show.
 */
public sealed interface DomainEvent
        permits BookingConfirmed, PaymentCompleted, ReservationExpired {

    /**
     * Identity of this emission, assigned once when the message is recorded.
     *
     * <p>Delivery is at-least-once, so a consumer will occasionally see the same
     * message twice. This is what lets it notice. It is stable across redelivery
     * precisely because it is assigned at record time and stored, rather than
     * generated when the message is sent.
     */
    UUID messageId();

    /** When the fact became true - which is commit time, not send time. */
    Instant occurredAt();
}
