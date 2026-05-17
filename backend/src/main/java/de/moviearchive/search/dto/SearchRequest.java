package de.moviearchive.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchRequest {

    /** Free-text query. Null or blank = match_all (returns all films). */
    private String query;

    /** Optional advanced filter criteria. Each non-null field is applied as an AND filter. */
    @Valid
    private FilterCriteria filters;

    /**
     * Sort order. Allowed values: title_asc, year_desc, rating_desc, imdb_desc.
     * Defaults to title_asc when null or unrecognised.
     */
    private String sort;

    /** Zero-based page number for pagination (PAGE_SIZE=20 per page). */
    @Min(0)
    private int page;
}
