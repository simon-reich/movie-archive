package de.moviearchive.settings.dto;

import jakarta.validation.constraints.NotBlank;

public record SaveApiKeyRequest(@NotBlank String key) {
}
