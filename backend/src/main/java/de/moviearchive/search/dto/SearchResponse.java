package de.moviearchive.search.dto;

import java.util.List;

public record SearchResponse(
    List<SearchResultItem> results,
    long total,
    int page,
    int totalPages,
    boolean hasMore
) {}
