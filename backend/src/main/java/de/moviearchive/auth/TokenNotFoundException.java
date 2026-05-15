package de.moviearchive.auth;

public class TokenNotFoundException extends RuntimeException {
    public TokenNotFoundException() {
        super("Invalid token.");
    }

    public TokenNotFoundException(String message) {
        super(message);
    }
}
