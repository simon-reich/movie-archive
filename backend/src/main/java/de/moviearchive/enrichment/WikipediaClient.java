package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Fallback backoff (seconds) when a 429 arrives with no parseable Retry-After header.
     * Observed live Retry-After values were ~55-56s; this is a conservative default for
     * the rare case the header is missing or malformed.
     */
    @Value("${wikipedia.rate-limit-fallback-backoff-s:30}")
    private long fallbackBackoffSeconds;

    /** Upper bound on how long a single 429 is allowed to stall the batch. */
    @Value("${wikipedia.rate-limit-max-backoff-s:120}")
    private long maxBackoffSeconds;

    /**
     * Shared across all requests from this client (a singleton bean): once ANY request
     * gets 429'd, every subsequent request — for this movie AND every movie still queued
     * behind it — waits out the SAME backoff window before trying again. Fixed per-request
     * pacing alone (paceRequest) was not sufficient: live testing showed even a
     * conservative 1000ms/request pace still gets 429'd under a real batch's sustained
     * request volume, because Wikipedia's anonymous-API limiter tracks a longer rolling
     * window than a single request's own spacing. Blindly moving on to the next candidate
     * after a 429 (the old behavior) just accumulates MORE 429s in that same window,
     * turning one rate-limit hit into an entire batch of false "not found" results.
     */
    private final AtomicReference<Instant> backoffUntil = new AtomicReference<>(Instant.EPOCH);

    public WikipediaClient(WebClient.Builder builder,
                           @Value("${wikipedia.base-url:https://en.wikipedia.org}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "MovieArchive/0.1")
                .build();
    }

    private void paceRequest() {
        Instant waitUntil = backoffUntil.get();
        Duration remaining = Duration.between(Instant.now(), waitUntil);
        if (!remaining.isNegative() && !remaining.isZero()) {
            log.warn("Wikipedia rate-limit backoff in effect — waiting {}s before next request", remaining.toSeconds());
            sleepQuietly(remaining.toMillis());
        }
        sleepQuietly(requestPacingMs);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records a 429 by pushing the shared backoff window forward. Uses the response's
     * Retry-After header (seconds) when present and parseable, capped at
     * maxBackoffSeconds; falls back to fallbackBackoffSeconds otherwise. Never SHORTENS
     * an existing backoff window — if two requests 429 in close succession, the later
     * (larger) window wins only if it is actually longer.
     */
    private void recordRateLimited(WebClientResponseException e, String context) {
        long seconds = fallbackBackoffSeconds;
        String retryAfter = e.getHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                seconds = Long.parseLong(retryAfter.trim());
            } catch (NumberFormatException ignored) {
                // Retry-After can also be an HTTP-date; not handled — fall back to default.
            }
        }
        seconds = Math.min(seconds, maxBackoffSeconds);
        Instant candidate = Instant.now().plusSeconds(seconds);
        backoffUntil.updateAndGet(current -> candidate.isAfter(current) ? candidate : current);
        log.warn("Wikipedia API rate-limited (429) for {} — backing off {}s. This is NOT a genuine "
                + "'page not found'; results from this and nearby lookups are unreliable.", context, seconds);
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
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                recordRateLimited(e, "search term=" + searchTerm);
            } else {
                log.debug("Wikipedia search API failed for term={} status={}", searchTerm, e.getStatusCode().value());
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
                recordRateLimited(e, "candidate=" + pageTitle);
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
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                recordRateLimited(e, "section=" + sectionIndex + " page=" + pageTitle);
            } else {
                log.debug("Failed to fetch section={} for page={}: status={}", sectionIndex, pageTitle, e.getStatusCode().value());
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
