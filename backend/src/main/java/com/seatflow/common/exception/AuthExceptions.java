package com.seatflow.common.exception;

/**
 * The authentication failures, grouped so the small ones do not each need a file.
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    public static class EmailAlreadyRegistered extends ApiException {
        public EmailAlreadyRegistered(String email) {
            super(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account already exists for this email address.");
            with("email", email);
        }
    }

    /**
     * Deliberately identical whether the account is missing or the password is
     * wrong. Distinguishing them turns the login endpoint into an account
     * enumeration oracle.
     */
    public static class InvalidCredentials extends ApiException {
        public InvalidCredentials() {
            super(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
        }
    }

    public static class InvalidRefreshToken extends ApiException {
        public InvalidRefreshToken(String detail) {
            super(ErrorCode.INVALID_REFRESH_TOKEN, detail);
        }
    }

    public static class AccountDisabled extends ApiException {
        public AccountDisabled() {
            super(ErrorCode.ACCOUNT_DISABLED, "This account has been disabled.");
        }
    }
}
