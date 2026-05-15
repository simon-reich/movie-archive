package de.moviearchive.auth;

public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException() {
        super("Account not verified.");
    }

    public AccountNotActiveException(String message) {
        super(message);
    }
}
