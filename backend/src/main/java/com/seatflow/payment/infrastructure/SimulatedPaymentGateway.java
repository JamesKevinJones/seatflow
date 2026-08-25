package com.seatflow.payment.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Stands in for a card processor.
 * <p>
 * Deterministic, not random: a payment method beginning {@code decline} fails,
 * anything else succeeds. A gateway that failed one call in twenty would make
 * the failure path impossible to test and the test suite flaky, which is the
 * opposite of useful.
 * <p>
 * The artificial latency matters more than it looks. A gateway that returns
 * instantly hides every race between the call and the hold expiring - the
 * window this design spends effort protecting. The delay keeps that window real
 * in development.
 */
@Component
public class SimulatedPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final long latencyMillis;

    public SimulatedPaymentGateway(
            @Value("${seatflow.payment.simulated-latency-ms:400}") long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    /**
     * Charges the instrument.
     * <p>
     * Called <em>outside</em> any transaction on purpose. Holding a database
     * connection open across a network call to a third party is how a slow
     * provider turns into an exhausted connection pool.
     */
    public Result charge(long amountCents, String paymentMethod) {
        sleepBriefly();

        String method = paymentMethod == null ? "" : paymentMethod.trim().toLowerCase(Locale.ROOT);

        if (method.startsWith("decline")) {
            log.info("Simulated gateway declined a charge of {} cents", amountCents);
            return new Result(false, null, "The payment method was declined.");
        }

        String reference = "sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("Simulated gateway accepted {} cents, reference {}", amountCents, reference);
        return new Result(true, reference, null);
    }

    private void sleepBriefly() {
        if (latencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What the provider said. */
    public record Result(boolean successful, String providerReference, String failureReason) {
    }
}
