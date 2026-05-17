package de.moviearchive.search.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FilterCriteria {

    private List<String> genres;
    private String director;
    private String actors;
    private Integer yearFrom;
    private Integer yearTo;
    private Double imdbRatingFrom;
    private Double imdbRatingTo;
    private List<String> contentRatings;
    private Integer runtimeMax;
    private Boolean notYetWatched;
    private List<String> languages;
    private List<String> countries;
}
