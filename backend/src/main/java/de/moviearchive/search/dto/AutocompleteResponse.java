package de.moviearchive.search.dto;

import java.util.List;

public record AutocompleteResponse(
    List<String> suggestions
) {}
