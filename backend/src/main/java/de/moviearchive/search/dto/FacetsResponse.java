package de.moviearchive.search.dto;

import java.util.List;

public record FacetsResponse(
    List<String> genres,
    List<String> contentRatings,
    List<String> languages,
    List<String> countries
) {}
