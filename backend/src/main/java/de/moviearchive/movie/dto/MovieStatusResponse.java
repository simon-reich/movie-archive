package de.moviearchive.movie.dto;

public record MovieStatusResponse(
    String id,
    String status,
    String title,
    String indexedAt
) {}
