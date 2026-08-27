package de.moviearchive.bulkimport.dto;

import java.util.UUID;

public record MatchedLine(UUID movieId, int tmdbId) {}
