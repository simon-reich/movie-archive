package de.moviearchive.movie.dto;

public record TmdbSearchResultItem(
    int tmdbId,
    String title,
    Integer year,
    String posterPath
) {}
