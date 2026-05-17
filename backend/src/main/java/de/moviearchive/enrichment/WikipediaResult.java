package de.moviearchive.enrichment;

public record WikipediaResult(
        String url,
        String summary,
        String plot,
        String critics
) {}
