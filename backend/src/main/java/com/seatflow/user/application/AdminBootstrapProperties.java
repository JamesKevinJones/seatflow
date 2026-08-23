package com.seatflow.user.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bootstrap administrator, bound from {@code seatflow.admin}.
 * <p>
 * Registration only ever grants USER, so without this there would be no way to
 * reach an admin endpoint on a fresh database.
 *
 * @param enabled  set false to skip bootstrapping entirely.
 * @param email    address of the account to create or promote.
 * @param password password used only when the account is being created.
 * @param fullName display name used only when the account is being created.
 */
@ConfigurationProperties(prefix = "seatflow.admin")
public record AdminBootstrapProperties(
        boolean enabled,
        String email,
        String password,
        String fullName) {

    /** Bootstrapping runs only when it is switched on and actually configured. */
    public boolean isConfigured() {
        return enabled
                && email != null && !email.isBlank()
                && password != null && !password.isBlank();
    }
}
