package com.seatflow.notification;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.notification.application.SeatUpdate;
import com.seatflow.notification.application.SeatUpdateSequence;
import com.seatflow.notification.infrastructure.SeatUpdateFanout;
import com.seatflow.user.application.AdminBootstrapProperties;
import com.seatflow.user.application.AdminBootstrapRunner;
import com.seatflow.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The things that are correct on one instance and wrong on two.
 *
 * <p>This is the awkward class of bug: none of it fails on a developer machine,
 * none of it logs an error, and all of it appears the first time a second
 * replica starts. Each test below corresponds to something that was genuinely
 * broken before this work and would have stayed silent.
 *
 * <p>One JVM is enough to prove all three, because in each case the thing being
 * shared is external - a Redis key, a Redis channel, a PostgreSQL advisory lock.
 * Two objects in one process contending over shared external state is the same
 * contention two processes would have.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MultiInstanceIT {

    @Autowired private SeatUpdateSequence sequence;
    @Autowired private SeatUpdateFanout fanout;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisMessageListenerContainer listenerContainer;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AdminBootstrapRunner adminBootstrap;
    @Autowired private AdminBootstrapProperties adminProperties;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Was a local {@code AtomicLong}. Two instances would each have started at 1,
     * so a browser receiving from both would see 1, 1, 2, 2 - read as a gap by
     * the client, which then re-fetches the entire seat map on every single
     * update. Live updates would appear to work while quietly costing a full
     * REST round trip each time.
     */
    @Test
    @DisplayName("The seat-update sequence is shared across instances")
    void sequenceIsSharedAcrossInstances() {
        UUID eventId = UUID.randomUUID();

        // A second instance, as far as the counter is concerned: its own object,
        // its own in-memory state, the same Redis.
        SeatUpdateSequence otherInstance = new SeatUpdateSequence(redis);

        List<Long> issued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            issued.add(sequence.next(eventId));
            issued.add(otherInstance.next(eventId));
        }

        assertThat(issued)
                .as("no number is issued twice, whichever instance asked")
                .doesNotHaveDuplicates()
                .isSorted()
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    /**
     * The fan-out itself. Before this, a seat taken on instance A was never
     * mentioned to anyone connected to instance B - Spring's simple broker only
     * knows its own JVM's sessions.
     */
    @Test
    @DisplayName("A seat update published on one instance reaches the others")
    void seatUpdatesReachOtherInstances() {
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        MessageListener otherInstance = (message, pattern) ->
                received.add(new String(message.getBody(), StandardCharsets.UTF_8));

        // Two channels: the real one, and a private one used only to find out
        // when the subscription is actually live. Redis pub/sub has no
        // buffering - a message published before the subscriber is registered is
        // simply dropped - so the test has to wait for readiness rather than
        // assume it. Probing on the real channel instead would push a
        // deliberately malformed message past the production subscriber, which
        // logs it as an error and makes every clean run look like a broken one.
        String probeChannel = "seatflow:test-probe:" + UUID.randomUUID();
        ConcurrentLinkedQueue<String> probes = new ConcurrentLinkedQueue<>();
        MessageListener probeListener = (message, pattern) -> probes.add("ready");

        listenerContainer.addMessageListener(otherInstance, new ChannelTopic(SeatUpdateFanout.CHANNEL));
        listenerContainer.addMessageListener(probeListener, new ChannelTopic(probeChannel));
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                redis.convertAndSend(probeChannel, "ping");
                return !probes.isEmpty();
            });

            UUID eventId = UUID.randomUUID();
            UUID seatId = UUID.randomUUID();
            SeatUpdate update = new SeatUpdate(
                    eventId, List.of(seatId.toString()), "RESERVED", 42L, Instant.now());

            fanout.publish(update);

            String delivered = await().atMost(Duration.ofSeconds(10))
                    .until(() -> received.stream().filter(m -> m.contains(seatId.toString())).findFirst()
                            .orElse(null), body -> body != null);

            SeatUpdate roundTripped = objectMapper.readValue(delivered, SeatUpdate.class);
            assertThat(roundTripped.eventId()).isEqualTo(eventId);
            assertThat(roundTripped.status()).isEqualTo("RESERVED");
            assertThat(roundTripped.seq()).isEqualTo(42L);
            assertThat(roundTripped.seatIds()).containsExactly(seatId.toString());
            // The destination a browser subscribes to has to survive the hop.
            assertThat(roundTripped.destination()).isEqualTo("/topic/events/" + eventId + "/seats");
        } finally {
            listenerContainer.removeMessageListener(otherInstance);
            listenerContainer.removeMessageListener(probeListener);
        }
    }

    /**
     * Startup, which is where a cluster is most likely to do everything at once.
     * <p>
     * Every instance of a fresh deployment reads "no admin" and inserts. All but
     * one hit {@code uq_users_email_lower}, and an {@code ApplicationRunner} that
     * throws stops the application - so the bug that cannot happen on one node
     * takes down most of a cluster on its first boot.
     */
    @Test
    @DisplayName("Several instances bootstrapping the admin at once produce one account")
    void concurrentAdminBootstrapCreatesOneAccount() throws Exception {
        String email = User.normalizeEmail(adminProperties.email());
        assertThat(adminProperties.isConfigured())
                .as("the test profile must configure an admin, or this proves nothing")
                .isTrue();

        // Start from nothing, so the create path is the one being raced.
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);

        int instances = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(instances);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // One thread per caller. A pool smaller than the task count would let
        // some tasks only begin after others finished, and nothing would race.
        try (ExecutorService pool = Executors.newFixedThreadPool(instances)) {
            for (int i = 0; i < instances; i++) {
                pool.submit(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        adminBootstrap.run(null);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures)
                .as("no instance may fail to start because another one won the race")
                .isEmpty();

        Integer accounts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(accounts).as("exactly one admin account").isEqualTo(1);
    }
}
