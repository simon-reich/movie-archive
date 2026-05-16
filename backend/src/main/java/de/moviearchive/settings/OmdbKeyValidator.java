package de.moviearchive.settings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Component
@Slf4j
public class OmdbKeyValidator {

    private final WebClient webClient;

    public OmdbKeyValidator(WebClient.Builder builder,
                            @Value("${omdb.base-url:https://www.omdbapi.com}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Returns true if the API key is valid (Response=True), false if invalid (Response=False or 401).
     * Throws on other HTTP errors or network issues.
     */
    @SuppressWarnings("unchecked")
    public boolean validate(String apiKey) {
        try {
            Map<String, Object> body = webClient.get()
                    .uri("/?apikey={key}&i=tt0111161", apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                log.debug("OMDB key validation: null response body");
                return false;
            }
            boolean valid = "True".equals(body.get("Response"));
            log.debug("OMDB key validation: result={}", valid);
            return valid;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                log.debug("OMDB key validation: result=false (401)");
                return false;
            }
            throw e;
        }
    }
}
