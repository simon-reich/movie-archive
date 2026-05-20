package de.moviearchive.search.dto;

import java.util.List;

public record SearchResultItem(
    String id,
    Integer tmdbId,
    String title,
    Integer year,
    String posterPath,
    List<String> directorList,
    List<String> genreList,
    Double imdbRating,
    Integer runtime,
    Double score
) {}
