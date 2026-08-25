package com.seatflow.notification.presentation;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket, for live seat updates.
 * <p>
 * The broker is Spring's simple in-memory one, which knows only about the STOMP
 * sessions attached to this JVM. That is a problem the moment there is a second
 * instance, and it is solved one layer up rather than here: every seat update is
 * published to a Redis channel that all instances subscribe to, and each
 * instance then delivers to its own sessions. See {@code SeatUpdateFanout}.
 * <p>
 * The alternative is an external STOMP broker - RabbitMQ or ActiveMQ - behind
 * {@code enableStompBrokerRelay}. That is the heavier and more capable answer,
 * and it would add a whole piece of infrastructure to solve a problem Redis
 * already solves here, for a feed where every message is disposable and clients
 * recover from a gap by re-fetching.
 * <p>
 * There is no inbound destination. Clients only subscribe; they never publish.
 * Seats change through the REST API, where the concurrency control lives, and
 * accepting seat commands over a socket would be a second, unguarded way in.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same origin in development via the Vite proxy, so no allowed-origins
        // list is needed yet. A deployed frontend on another origin will need one.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }
}
