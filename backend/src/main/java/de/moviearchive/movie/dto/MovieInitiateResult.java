package de.moviearchive.movie.dto;

import java.util.UUID;

public record MovieInitiateResult(UUID id, boolean isNew) {}
