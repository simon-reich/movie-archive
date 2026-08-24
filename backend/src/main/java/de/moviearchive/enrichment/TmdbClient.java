package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
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
        JsonNode response = webClient.get()
                .uri("/3/search/movie?query={q}&api_key={key}&language=en-US", query, apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<TmdbSearchResultItem> results = new ArrayList<>();
        if (response != null && response.has("results")) {
            for (JsonNode item : response.get("results")) {
                int tmdbId = item.get("id").asInt();
                String title = item.path("title").asText(null);
                String originalTitle = item.path("original_title").asText(null);
                String releaseDate = item.path("release_date").asText("");
                Integer year = null;
                if (releaseDate.length() >= 4) {
                    try {
                        year = Integer.parseInt(releaseDate.substring(0, 4));
                    } catch (NumberFormatException ignored) {
                        // Non-numeric release_date prefix — treat year as unknown
                    }
                }
                String posterPath = item.path("poster_path").asText(null);
                results.add(new TmdbSearchResultItem(tmdbId, title, originalTitle, year, posterPath));
            }
        }
        log.debug("TMDB search returned {} results for query={}", results.size(), query);
        return results;
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public JsonNode fetchDetail(int tmdbId, String apiKey) {
        log.debug("Fetching TMDB detail for tmdbId={}", tmdbId);
        return webClient.get()
                .uri("/3/movie/{id}?language=en-US&append_to_response=credits,keywords,videos,images,release_dates,external_ids&api_key={key}",
                        tmdbId, apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }
}
