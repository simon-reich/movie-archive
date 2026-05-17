package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class WikipediaClient {

    private final WebClient webClient;
    private final String baseUrl;

    public WikipediaClient(WebClient.Builder builder,
                           @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "MovieArchive/0.1")
                .build();
    }

    /**
     * Tries 6 title candidates in order. Returns a WikipediaResult on first hit.
     * Throws WikipediaNotFoundException after all 6 are exhausted.
     *
     * Candidate order (per CLAUDE.md Wikipedia 6-step fallback):
     * 1. {OriginalTitle}_{Year}_film
     * 2. {OriginalTitle}_(film)
     * 3. {OriginalTitle}
     * 4. {Title}_{Year}_film
     * 5. {Title}_(film)
     * 6. {Title}
     */
    @Retryable(retryFor = Exception.class,
               noRetryFor = WikipediaNotFoundException.class,
               maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public WikipediaResult fetch(String originalTitle, String title, int year) {
        List<String> candidates = buildCandidates(originalTitle, title, year);
        for (String candidate : candidates) {
            Optional<WikipediaResult> result = tryFetch(candidate);
            if (result.isPresent()) {
                log.debug("Wikipedia page found for candidate={}", candidate);
                return result.get();
            }
        }
        throw new WikipediaNotFoundException(
                "No Wikipedia page found after 6 attempts for titles: " + originalTitle + " / " + title);
    }

    List<String> buildCandidates(String originalTitle, String title, int year) {
        // Spaces → underscores (NOT URLEncoder.encode which uses +)
        String origSlug = originalTitle.replace(' ', '_');
        String titleSlug = title.replace(' ', '_');
        List<String> candidates = new ArrayList<>();
        candidates.add(origSlug + "_" + year + "_film");
        candidates.add(origSlug + "_(film)");
        candidates.add(origSlug);
        candidates.add(titleSlug + "_" + year + "_film");
        candidates.add(titleSlug + "_(film)");
        candidates.add(titleSlug);
        return candidates;
    }

    private Optional<WikipediaResult> tryFetch(String pageTitle) {
        try {
            // Step 1: Get sections list to confirm page exists and find Plot/Critical response indices
            JsonNode sectionsResponse = webClient.get()
                    .uri("/w/api.php?action=parse&page={title}&prop=sections&format=json", pageTitle)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (sectionsResponse == null || !sectionsResponse.has("parse")) {
                return Optional.empty();
            }

            String wikiUrl = baseUrl + "/wiki/" + pageTitle;
            JsonNode sections = sectionsResponse.get("parse").get("sections");

            // Step 2: Fetch section 0 (intro/summary) via prop=wikitext
            String summary = fetchSection(pageTitle, "0");

            // Step 3: Find and fetch Plot section
            String plotIndex = findSectionIndex(sections, "Plot");
            String plot = plotIndex != null ? fetchSection(pageTitle, plotIndex) : null;

            // Step 4: Find and fetch Critical response section
            String criticsIndex = findSectionIndex(sections, "Critical response");
            String critics = criticsIndex != null ? fetchSection(pageTitle, criticsIndex) : null;

            return Optional.of(new WikipediaResult(wikiUrl, summary, plot, critics));

        } catch (WebClientResponseException e) {
            log.debug("Wikipedia lookup failed for candidate={} status={}", pageTitle, e.getStatusCode().value());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Wikipedia lookup exception for candidate={}: {}", pageTitle, e.getMessage());
            return Optional.empty();
        }
    }

    private String fetchSection(String pageTitle, String sectionIndex) {
        try {
            JsonNode response = webClient.get()
                    .uri("/w/api.php?action=parse&page={title}&prop=wikitext&section={section}&format=json",
                            pageTitle, sectionIndex)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (response != null && response.has("parse")) {
                return response.get("parse").path("wikitext").path("*").asText(null);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch section={} for page={}: {}", sectionIndex, pageTitle, e.getMessage());
        }
        return null;
    }

    private String findSectionIndex(JsonNode sections, String sectionLine) {
        if (sections == null) return null;
        for (JsonNode section : sections) {
            if (sectionLine.equalsIgnoreCase(section.path("line").asText(""))) {
                return section.path("index").asText(null);
            }
        }
        return null;
    }
}
