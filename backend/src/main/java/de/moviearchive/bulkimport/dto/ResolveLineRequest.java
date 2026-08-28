package de.moviearchive.bulkimport.dto;

import jakarta.validation.constraints.Positive;

/**
 * D-08: request body for resolving an AMBIGUOUS/NOT_FOUND BulkImportLine via a manually
 * picked TMDB search candidate. posterPath is nullable — the picked candidate's posterPath
 * may be null for a TMDB result with no poster.
 */
public record ResolveLineRequest(
    @Positive(message = "tmdbId must be a positive integer")
    int tmdbId,
    String posterPath
) {}
