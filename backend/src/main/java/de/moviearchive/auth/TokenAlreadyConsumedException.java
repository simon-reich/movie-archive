package de.moviearchive.auth;

public class TokenAlreadyConsumedException extends RuntimeException {
    public TokenAlreadyConsumedException() {
        super("Token already used.");
    }

    public TokenAlreadyConsumedException(String message) {
        super(message);
    }
}
