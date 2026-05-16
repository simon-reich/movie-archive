package de.moviearchive.movie;

public class NoTmdbKeyException extends RuntimeException {
    public NoTmdbKeyException(String message) {
        super(message);
    }
}
