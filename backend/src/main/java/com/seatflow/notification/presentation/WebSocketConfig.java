package com.seatflow.notification.presentation;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket, for live seat updates.
 * <p>
 * The broker is Spring's simple in-memory one. That is honest for a single
 * instance and wrong for several: each would broadcast only to its own clients.
 * Going multi-instance means a real relay - Redis pub/sub or an external STOMP
 * broker - which is noted rather than pretended.
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
