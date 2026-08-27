package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieStatus;
import de.moviearchive.settings.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EnrichmentService {

    private final MovieRepository movieRepository;
    private final SettingsService settingsService;
    private final TmdbClient tmdbClient;
    private final OmdbClient omdbClient;
    private final WikipediaClient wikipediaClient;
    private final IndexingService indexingService;

    public EnrichmentService(MovieRepository movieRepository,
                             SettingsService settingsService,
                             TmdbClient tmdbClient,
                             OmdbClient omdbClient,
                             WikipediaClient wikipediaClient,
                             IndexingService indexingService) {
        this.movieRepository = movieRepository;
        this.settingsService = settingsService;
        this.tmdbClient = tmdbClient;
        this.omdbClient = omdbClient;
        this.wikipediaClient = wikipediaClient;
        this.indexingService = indexingService;
    }

    /**
     * Async enrichment pipeline: TMDB -> OMDB (optional) -> Wikipedia -> Postgres.
     * Any failure while loading the movie/API keys or during the mandatory TMDB step sets
     * status=ERROR. OMDB and Wikipedia failures are silent (D-15).
     * MUST be called from a different bean (MovieController) — self-invocation bypasses @Async proxy.
     */
    @Async("enrichmentExecutor")
    @Transactional
    public void enrich(UUID movieId) {
        doEnrich(movieId, null);
    }

    /**
     * Same as {@link #enrich(UUID)}, but accepts a batch caller's pre-resolved
     * IMDb-id-to-enwiki-title map (Phase 13 D-03) — used by {@code BulkImportService}'s
     * two-pass restructuring so the Wikipedia step below skips a per-movie SPARQL call
     * entirely, threading the SAME map into {@link WikipediaClient#fetch(String, String, int,
     * String, Map)} for every matched line in a run.
     */
    @Async("enrichmentExecutor")
    @Transactional
    public void enrich(UUID movieId, Map<String, String> preResolvedWikiTitles) {
        doEnrich(movieId, preResolvedWikiTitles);
    }

    private void doEnrich(UUID movieId, Map<String, String> preResolvedWikiTitles) {
        try {
            // JOIN FETCH user to avoid LazyInitializationException on async thread
            Movie movie = movieRepository.findByIdWithUser(movieId)
                    .orElseThrow(() -> new IllegalStateException("Movie not found for enrichment: " + movieId));

            String email = movie.getUser().getEmail();
            Map<String, Object> keys = settingsService.getApiKeys(email);
            String tmdbKey = (String) keys.get("tmdb");

            // === Step 1: TMDB detail (MANDATORY) ===
            log.info("Enriching movieId={} tmdbId={}", movieId, movie.getTmdbId());
            JsonNode tmdbDetail = tmdbClient.fetchDetail(movie.getTmdbId(), tmdbKey);
            movie.setRawTmdbJson(tmdbDetail);
            movie.setTitle(tmdbDetail.path("title").asText(null));
            movie.setOriginalTitle(tmdbDetail.path("original_title").asText(null));
            String releaseDate = tmdbDetail.path("release_date").asText(null);
            if (releaseDate != null && !releaseDate.isBlank()) {
                movie.setReleaseDate(LocalDate.parse(releaseDate));
            }
            int runtimeVal = tmdbDetail.path("runtime").asInt(0);
            movie.setRuntime(runtimeVal > 0 ? runtimeVal : null);
            // imdb_id comes from external_ids.imdb_id (appended via append_to_response)
            String imdbId = tmdbDetail.path("external_ids").path("imdb_id").asText(null);
            if (imdbId != null && imdbId.isBlank()) {
                imdbId = null;
            }
            movie.setImdbId(imdbId);

            // === Step 2: OMDB (OPTIONAL — skip if no key OR no imdb_id) ===
            String omdbKey = (String) keys.get("omdb");
            if (omdbKey != null && movie.getImdbId() != null) {
                try {
                    JsonNode omdbData = omdbClient.fetch(movie.getImdbId(), omdbKey);
                    movie.setRawOmdbJson(omdbData);
                    log.info("OMDB data fetched for movieId={}", movieId);
                } catch (Exception e) {
                    log.warn("OMDB enrichment failed for movieId={} — continuing without OMDB data: {}",
                            movieId, e.getMessage());
                }
            } else {
                log.debug("OMDB skipped for movieId={}: omdbKey={}, imdbId={}",
                        movieId, omdbKey != null ? "present" : "null", movie.getImdbId());
            }

            // === Step 3: Wikipedia (OPTIONAL — 6-step fallback, always silent on failure) ===
            try {
                movie.setWikiLastAttemptedAt(Instant.now());
                int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
                String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
                String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
                WikipediaResult wiki = preResolvedWikiTitles != null
                        ? wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId(), preResolvedWikiTitles)
                        : wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId());
                movie.setWikiUrl(wiki.url());
                movie.setWikiSummary(wiki.summary());
                movie.setWikiPlot(wiki.plot());
                movie.setWikiCritics(wiki.critics());
                log.info("Wikipedia data fetched for movieId={}", movieId);
            } catch (WikipediaNotFoundException e) {
                log.warn("Wikipedia: no page found for movieId={} after 6 attempts — saving without wiki data", movieId);
            } catch (Exception e) {
                log.warn("Wikipedia enrichment failed for movieId={} — continuing without wiki data: {}",
                        movieId, e.getMessage());
            }

            // === Step 4: Persist with SUCCESS ===
            movie.setStatus(MovieStatus.SUCCESS);
            movieRepository.save(movie);
            log.info("Enrichment complete for movieId={} status=SUCCESS", movieId);

            // === Step 5: OpenSearch index (silent on failure — D-01) ===
            try {
                indexingService.index(movie);
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
                log.info("OpenSearch indexed movieId={}", movieId);
            } catch (Exception e) {
                log.warn("OpenSearch indexing failed for movieId={} — indexed_at stays null: {}",
                        movieId, e.getMessage());
                // D-01: status stays SUCCESS, no rethrow
            }

        } catch (Exception e) {
            // Any failure before or during enrichment (including API-key decrypt failures
            // in settingsService.getApiKeys(), previously outside this try block) -> ERROR
            // status. If the movie itself couldn't be loaded, there's nothing to update.
            log.error("Enrichment failed for movieId={} — setting status=ERROR", movieId, e);
            movieRepository.findById(movieId).ifPresent(m -> {
                m.setStatus(MovieStatus.ERROR);
                movieRepository.save(m);
            });
        }
    }
}
