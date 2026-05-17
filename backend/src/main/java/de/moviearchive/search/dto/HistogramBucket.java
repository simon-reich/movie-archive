package de.moviearchive.search.dto;

public record HistogramBucket(
    String label,
    long count
) {}
