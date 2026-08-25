package com.seatflow.messaging.contract;

/**
 * Topic names, which are part of the published contract just as much as the
 * payload fields are.
 *
 * <p>Named for facts in the past tense, not for commands. {@code booking.confirmed}
 * says a thing happened and leaves consumers free to decide whether they care;
 * a topic called {@code send-confirmation-email} would be one service reaching
 * into another and calling it events.
 */
public final class Topics {

    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String RESERVATION_EXPIRED = "reservation.expired";
    public static final String PAYMENT_COMPLETED = "payment.completed";

    /**
     * Every topic is partitioned by the show, so all messages about one event
     * land on one partition and are therefore delivered in order relative to
     * each other. Partitioning by booking id would spread load more evenly but
     * would make "sold, then refunded" arrive in either order.
     *
     * <p>Three partitions is enough for a single-venue demonstration and keeps
     * the ordering guarantee meaningful. Raising it later is safe; lowering it
     * is not, because keys would move between partitions.
     */
    public static final int PARTITIONS = 3;

    /**
     * One broker in development, so nothing can be replicated further than that.
     * A real cluster wants at least 3 with {@code min.insync.replicas=2}.
     */
    public static final short REPLICATION_FACTOR = 1;

    private Topics() {
    }
}
