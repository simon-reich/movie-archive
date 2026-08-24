package de.moviearchive.movie.dto;

public record TmdbSearchResultItem(
    int tmdbId,
    String title,
    String originalTitle,
    Integer year,
    String posterPath
) {}
