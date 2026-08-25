package com.seatflow.notification.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * The container that subscribes this instance to the cluster's seat-update
 * channel.
 *
 * <p><b>{@code setAutoStartup(false)} is the important line, and it was added
 * after this configuration broke the build.</b> A
 * {@code RedisMessageListenerContainer} with a listener on it opens its
 * subscription when the context starts, and if Redis is unreachable at that
 * moment it throws - which surfaces as
 * {@code ApplicationContextException: Failed to start bean} and the application
 * does not start at all.
 *
 * <p>That is a direct violation of the rule the whole design rests on: Redis is
 * never allowed to matter. An unreachable cache must cost latency, never
 * availability, and "the ticket platform will not boot because the cache is
 * down" is the worst possible version of the coupling this project spends so
 * much effort avoiding. It was invisible until the Redis timeout was tightened
 * and the test suite could no longer connect within it.
 *
 * <p>So the container starts outside the context lifecycle, from
 * {@link SeatUpdateSubscription}, where a failure is a log line and a retry.
 * Once running, {@code recoveryInterval} handles reconnects on its own - which
 * matters because without it a Redis restart would leave this instance silently
 * unsubscribed, still serving pages and still selling seats, but never again
 * telling anyone's browser that a seat had changed.
 *
 * <p>Marked {@link Primary} because Boot auto-configures a
 * {@code RedisMessageListenerContainer} of its own; this is the one that carries
 * the fan-out and the one anything else should be given.
 */
@Configuration
public class RedisFanoutConfig {

    /** How long the container waits before re-subscribing after a connection is lost. */
    private static final long RECOVERY_INTERVAL_MS = 5_000;

    @Bean
    @Primary
    RedisMessageListenerContainer seatUpdateListenerContainer(
            RedisConnectionFactory connectionFactory, SeatUpdateSubscriber subscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(SeatUpdateFanout.CHANNEL));
        container.setRecoveryInterval(RECOVERY_INTERVAL_MS);

        // Started by SeatUpdateSubscription, not by the context. See above.
        container.setAutoStartup(false);
        return container;
    }
}
