package de.moviearchive.search.dto;

public record DashboardMovieItem(
    String id,
    String title,
    Integer year,
    String posterPath
) {}
