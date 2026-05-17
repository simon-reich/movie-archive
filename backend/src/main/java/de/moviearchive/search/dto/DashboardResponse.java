package de.moviearchive.search.dto;

import java.util.List;

public record DashboardResponse(
    long totalFilms,
    List<GenreCount> topGenres,
    List<LanguageCount> languageBreakdown,
    List<HistogramBucket> imdbHistogram,
    DashboardMovieItem movieOfTheDay,
    List<DashboardMovieItem> recentlyAdded
) {

    /** Top genre entry — maps to JSON keys `name` and `count`. */
    public record GenreCount(String name, long count) {}

    /** Language breakdown entry — maps to JSON keys `code` and `count`. */
    public record LanguageCount(String code, long count) {}
}
