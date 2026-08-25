package com.seatflow.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Which instance this is.
 *
 * <p>Irrelevant with one node and the first thing you want with several. When
 * two instances share a database, a broker and a load balancer, every log line
 * and every metric becomes ambiguous without it: "the sweeper ran" and "the
 * relay published 40 messages" stop being answerable questions.
 *
 * <p>The container hostname is the id, because that is what
 * {@code docker compose ps} shows and what a reader can map back to a container.
 * The random suffix only appears if the hostname cannot be resolved.
 */
@Component
public class InstanceIdentity {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdentity.class);

    private final String id;
    private final MeterRegistry registry;

    public InstanceIdentity(MeterRegistry registry) {
        this.registry = registry;
        this.id = resolve();
    }

    private static String resolve() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                return hostname;
            }
        } catch (UnknownHostException e) {
            // Falls through to the random id below.
        }
        return "instance-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Tags every metric with the instance, so a Prometheus scrape of two
     * instances produces two series rather than one that flickers between them.
     */
    @PostConstruct
    void tagMetrics() {
        registry.config().commonTags("instance", id);
        log.info("SeatFlow instance {} starting", id);
    }

    public String id() {
        return id;
    }
}
