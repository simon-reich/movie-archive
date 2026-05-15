package de.moviearchive.auth;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException() {
        super("An account with this email already exists.");
    }

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
