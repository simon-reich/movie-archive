package de.moviearchive.enrichment;

import de.moviearchive.movie.dto.TmdbSearchResultItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
@Slf4j
public class TmdbClient {

    private final WebClient webClient;

    public TmdbClient(WebClient.Builder builder,
                      @Value("${tmdb.base-url:https://api.themoviedb.org}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public List<TmdbSearchResultItem> search(String query, String apiKey) {
        // Stub: Plan 03-04 implements the real WebClient call
        // Returns empty list until enrichment plan completes
        return List.of();
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public Object fetchDetail(int tmdbId, String apiKey) {
        // Stub: Plan 03-04 implements the real WebClient call
        return null;
    }
}
