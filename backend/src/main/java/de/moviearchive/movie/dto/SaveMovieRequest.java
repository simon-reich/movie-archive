package de.moviearchive.movie.dto;

import jakarta.validation.constraints.Positive;

public record SaveMovieRequest(
    @Positive(message = "tmdbId must be a positive integer")
    int tmdbId
) {}
