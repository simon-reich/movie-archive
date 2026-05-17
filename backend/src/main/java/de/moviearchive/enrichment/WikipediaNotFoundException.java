package de.moviearchive.enrichment;

public class WikipediaNotFoundException extends RuntimeException {
    public WikipediaNotFoundException(String message) {
        super(message);
    }
}
