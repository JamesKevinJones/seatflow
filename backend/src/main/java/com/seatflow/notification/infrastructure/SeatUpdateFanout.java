package com.seatflow.notification.infrastructure;

import com.seatflow.notification.application.SeatUpdate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends a seat update to every instance, not just this one.
 *
 * <p>Spring's simple STOMP broker only knows about the sessions attached to its
 * own JVM. Behind a load balancer that is a silent bug rather than a loud one:
 * a user connected to instance B watches a seat get taken on instance A and sees
 * nothing happen, and the seat map is wrong until they reload. Nothing errors,
 * nothing is logged, and it only appears once there is more than one instance.
 *
 * <p>So the update is published to a Redis channel that every instance
 * subscribes to - <b>including the one that produced it</b>. Publishing here and
 * also delivering locally would double every message for the originating
 * instance's own clients, so this method does not deliver; it publishes, and
 * {@link SeatUpdateSubscriber} delivers on all instances alike. One path, one
 * copy each.
 *
 * <p><b>Why Redis rather than Kafka.</b> These need opposite semantics from
 * domain events. A confirmation email must be handled once across the cluster,
 * which is what a Kafka consumer group gives; a seat update must reach every
 * instance that holds a subscriber, which is what pub/sub gives. Kafka would
 * also add its poll interval to a path where latency is the entire feature.
 *
 * <p><b>Redis still does not matter.</b> If the publish fails, this delivers to
 * local subscribers directly - so an outage costs the other instances' clients a
 * broadcast, and those clients detect the sequence gap and re-fetch. Degraded,
 * never wrong.
 */
@Component
public class SeatUpdateFanout {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateFanout.class);

    /** Every instance publishes to and subscribes to this one channel. */
    public static final String CHANNEL = "seatflow:seat-updates";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SeatUpdateDelivery delivery;
    private final Counter degraded;

    public SeatUpdateFanout(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            SeatUpdateDelivery delivery,
            MeterRegistry registry) {

        this.redis = redis;
        this.objectMapper = objectMapper;
        this.delivery = delivery;
        this.degraded = Counter.builder("seatflow.fanout.degraded")
                .description("Seat updates delivered only locally because the fan-out channel was unreachable")
                .register(registry);
    }

    public void publish(SeatUpdate update) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(update));
        } catch (RuntimeException e) {
            // Other instances will not hear about this one. Their clients will
            // notice the gap in seq and re-fetch, which is the designed
            // behaviour for a missed message.
            degraded.increment();
            log.warn("Seat update fan-out failed, delivering locally only: {}", e.getMessage());
            delivery.toLocalSubscribers(update);
        }
    }
}
