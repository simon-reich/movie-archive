package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Slf4j
public class WikipediaClient {

    private final WebClient webClient;
    private final String baseUrl;

    // Patterns for wikitext cleanup — compiled once at class load
    private static final Pattern REF_BLOCK   = Pattern.compile("<ref[^>]*>.*?</ref>", Pattern.DOTALL);
    private static final Pattern REF_SELF    = Pattern.compile("<ref[^/]*/>");
    private static final Pattern TEMPLATE    = Pattern.compile("\\{\\{[^{}]*(?:\\{\\{[^{}]*}}[^{}]*)*}}", Pattern.DOTALL);
    private static final Pattern WIKI_PIPE   = Pattern.compile("\\[\\[(?:[^|\\]]+\\|)?([^\\]]+)]]");
    private static final Pattern SECTION_HDR = Pattern.compile("={2,}[^=\n]+={2,}\\s*");
    private static final Pattern HTML_TAG    = Pattern.compile("<[^>]+>");
    private static final Pattern BOLD_ITALIC = Pattern.compile("'{2,3}");
    private static final Pattern MULTI_NL    = Pattern.compile("\n{3,}");

    /**
     * Minimum delay before EVERY outbound Wikipedia API call, not just between movies.
     * Wikipedia's anonymous-API rate limiter blocks after ~10 requests fired with <300ms
     * spacing (empirically confirmed: HTTP 429 with Retry-After ~55s), but tolerates a
     * sustained ~1 req/s rate indefinitely. A single movie lookup can fire up to ~18
     * requests (10 title candidates + 2 search-fallback terms, each up to 4 requests when
     * a page is found), all previously fired back-to-back with zero delay between them —
     * batchReload's pacingDelayMs only throttled between MOVIES, never between the many
     * requests a single movie's candidate search makes, so a batch of any real size
     * tripped the 429 wall almost immediately and every subsequent lookup silently
     * degraded to Optional.empty() (misreported as "no Wikipedia page found").
     */
    @Value("${wikipedia.request-pacing-ms:1000}")
    private long requestPacingMs;

    public WikipediaClient(WebClient.Builder builder,
                           @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "MovieArchive/0.1")
                .build();
    }

    private void paceRequest() {
        try {
            Thread.sleep(requestPacingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tries title candidates in order, then falls back to the Wikipedia search API.
     * Throws WikipediaNotFoundException after all attempts are exhausted.
     *
     * Candidate order:
     * 1. {OriginalTitle}_(Year_film)   — e.g. Falcon_Lake_(2022_film)
     * 2. {OriginalTitle}_(film)        — e.g. Falcon_Lake_(film)
     * 3. {OriginalTitle}_Year_film     — legacy pattern kept as fallback
     * 4. {OriginalTitle}               — plain title
     * 5–8: same four patterns for {Title} when it differs from {OriginalTitle}
     * 9. Wikipedia search API for {OriginalTitle} + film
     * 10. Wikipedia search API for {Title} + film
     */
    public WikipediaResult fetch(String originalTitle, String title, int year) {
        List<String> candidates = buildCandidates(originalTitle, title, year);
        for (String candidate : candidates) {
            Optional<WikipediaResult> result = tryFetch(candidate);
            if (result.isPresent()) {
                log.debug("Wikipedia page found for candidate={}", candidate);
                return result.get();
            }
        }
        // Search API fallback
        for (String searchTerm : List.of(originalTitle, title).stream().distinct().toList()) {
            Optional<WikipediaResult> result = tryFetchViaSearch(searchTerm, year);
            if (result.isPresent()) {
                log.debug("Wikipedia page found via search for term={}", searchTerm);
                return result.get();
            }
        }
        throw new WikipediaNotFoundException(
                "No Wikipedia page found for titles: " + originalTitle + " / " + title);
    }

    List<String> buildCandidates(String originalTitle, String title, int year) {
        String origSlug  = originalTitle.replace(' ', '_');
        String titleSlug = title.replace(' ', '_');
        List<String> candidates = new ArrayList<>();
        addPatternsFor(candidates, origSlug, year);
        if (!titleSlug.equals(origSlug)) {
            addPatternsFor(candidates, titleSlug, year);
        }
        return candidates;
    }

    private void addPatternsFor(List<String> candidates, String slug, int year) {
        candidates.add(slug + "_(" + year + "_film)");   // Falcon_Lake_(2022_film)
        candidates.add(slug + "_(film)");                 // Falcon_Lake_(film)
        candidates.add(slug + "_" + year + "_film");      // legacy: Falcon_Lake_2022_film
        candidates.add(slug);                             // Falcon_Lake
    }

    private Optional<WikipediaResult> tryFetchViaSearch(String searchTerm, int year) {
        try {
            paceRequest();
            JsonNode response = webClient.get()
                    .uri("/w/api.php?action=query&list=search&srsearch={q}+film&srlimit=5&format=json",
                            searchTerm + " " + year)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (response == null) return Optional.empty();
            JsonNode hits = response.path("query").path("search");
            if (!hits.isArray()) return Optional.empty();
            for (JsonNode hit : hits) {
                String pageTitle = hit.path("title").asText("");
                if (pageTitle.isBlank()) continue;
                String slug = pageTitle.replace(' ', '_');
                Optional<WikipediaResult> result = tryFetch(slug);
                if (result.isPresent()) return result;
            }
        } catch (Exception e) {
            log.debug("Wikipedia search API exception for term={}: {}", searchTerm, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<WikipediaResult> tryFetch(String pageTitle) {
        try {
            paceRequest();
            // String concat avoids Spring's URI template encoding of ( and ) to %28/%29
            JsonNode sectionsResponse = webClient.get()
                    .uri("/w/api.php?action=parse&page=" + pageTitle + "&prop=sections&redirects=1&format=json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (sectionsResponse == null || !sectionsResponse.has("parse")) {
                return Optional.empty();
            }

            String resolvedTitle = sectionsResponse.path("parse").path("title").asText(pageTitle);
            String resolvedSlug  = resolvedTitle.replace(' ', '_');
            String wikiUrl = baseUrl + "/wiki/" + resolvedSlug;
            JsonNode sections = sectionsResponse.get("parse").get("sections");

            String summary = cleanWikitext(fetchSection(resolvedSlug, "0"));
            String plotIndex = findSectionIndex(sections, "Plot");
            String plot = plotIndex != null ? cleanWikitext(fetchSection(resolvedSlug, plotIndex)) : null;
            String criticsIndex = findSectionIndex(sections, "Critical response", "Reception", "Critical reception");
            String critics = criticsIndex != null ? cleanWikitext(fetchSection(resolvedSlug, criticsIndex)) : null;

            return Optional.of(new WikipediaResult(wikiUrl, summary, plot, critics));

        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Wikipedia API rate-limited (429) for candidate={} — this is NOT a genuine "
                        + "'page not found', results for this and nearby lookups are unreliable", pageTitle);
            } else {
                log.debug("Wikipedia lookup failed for candidate={} status={}", pageTitle, e.getStatusCode().value());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Wikipedia lookup exception for candidate={}: {}", pageTitle, e.getMessage());
            return Optional.empty();
        }
    }

    private String fetchSection(String pageTitle, String sectionIndex) {
        try {
            paceRequest();
            JsonNode response = webClient.get()
                    .uri("/w/api.php?action=parse&page=" + pageTitle + "&prop=wikitext&section=" + sectionIndex + "&redirects=1&format=json")
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

    /** Accepts multiple alternate section names; returns index of first match. */
    private String findSectionIndex(JsonNode sections, String... names) {
        if (sections == null) return null;
        for (String name : names) {
            for (JsonNode section : sections) {
                if (name.equalsIgnoreCase(section.path("line").asText(""))) {
                    return section.path("index").asText(null);
                }
            }
        }
        return null;
    }

    /**
     * Strips MediaWiki markup from raw wikitext:
     * - removes <ref>…</ref> and self-closing <ref/> citation tags
     * - removes {{template}} blocks (up to two nesting levels)
     * - resolves [[link|display]] → display, [[link]] → link
     * - removes ==Section headers==
     * - removes remaining HTML tags
     * - removes ''italic'' / '''bold''' markers
     * - collapses excess blank lines
     */
    static String cleanWikitext(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw;
        s = REF_BLOCK.matcher(s).replaceAll("");
        s = REF_SELF.matcher(s).replaceAll("");
        // Run template removal twice to handle one level of nesting
        s = TEMPLATE.matcher(s).replaceAll("");
        s = TEMPLATE.matcher(s).replaceAll("");
        s = WIKI_PIPE.matcher(s).replaceAll("$1");
        s = SECTION_HDR.matcher(s).replaceAll("");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = BOLD_ITALIC.matcher(s).replaceAll("");
        s = MULTI_NL.matcher(s).replaceAll("\n\n");
        s = s.strip();
        return s.isBlank() ? null : s;
    }
}
