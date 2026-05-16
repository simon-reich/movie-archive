package de.moviearchive.settings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@Slf4j
public class TmdbKeyValidator {

    private final WebClient webClient;

    public TmdbKeyValidator(WebClient.Builder builder,
                            @Value("${tmdb.base-url:https://api.themoviedb.org}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Returns true if the API key is valid (HTTP 200), false if invalid (HTTP 401).
     * Throws on other HTTP errors or network issues.
     */
    public boolean validate(String apiKey) {
        try {
            webClient.get()
                    .uri("/3/configuration?api_key={key}", apiKey)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.debug("TMDB key validation: result=true");
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.debug("TMDB key validation: result=false (401)");
                return false;
            }
            throw e;
        }
    }
}
