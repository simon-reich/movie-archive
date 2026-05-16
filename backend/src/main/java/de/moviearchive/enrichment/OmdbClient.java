package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@Slf4j
public class OmdbClient {

    private final WebClient webClient;

    public OmdbClient(WebClient.Builder builder,
                      @Value("${omdb.base-url:https://www.omdbapi.com}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public JsonNode fetch(String imdbId, String apiKey) {
        log.debug("Fetching OMDB data for imdbId={}", imdbId);
        return webClient.get()
                .uri("/?apikey={key}&i={imdbId}&plot=full", apiKey, imdbId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }
}
