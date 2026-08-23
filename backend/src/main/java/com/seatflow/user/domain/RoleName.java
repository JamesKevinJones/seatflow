package com.seatflow.user.domain;

/**
 * The roles the application knows about.
 * <p>
 * Kept in step with the {@code ck_roles_name_known} check constraint in
 * {@code V1__users_roles_auth.sql}. Adding a value here without a migration
 * will fail at insert time, which is the intended behaviour.
 */
public enum RoleName {

    USER,
    ADMIN;

    /** The authority name Spring Security expects, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
