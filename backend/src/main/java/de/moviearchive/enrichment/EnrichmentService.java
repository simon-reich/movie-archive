package de.moviearchive.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieStatus;
import de.moviearchive.settings.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public EnrichmentService(MovieRepository movieRepository,
                             SettingsService settingsService,
                             TmdbClient tmdbClient,
                             OmdbClient omdbClient,
                             WikipediaClient wikipediaClient) {
        this.movieRepository = movieRepository;
        this.settingsService = settingsService;
        this.tmdbClient = tmdbClient;
        this.omdbClient = omdbClient;
        this.wikipediaClient = wikipediaClient;
    }

    /**
     * Async enrichment pipeline: TMDB -> OMDB (optional) -> Wikipedia -> Postgres.
     * Only TMDB failure sets status=ERROR. OMDB and Wikipedia failures are silent (D-15).
     * MUST be called from a different bean (MovieController) — self-invocation bypasses @Async proxy.
     */
    @Async("enrichmentExecutor")
    @Transactional
    public void enrich(UUID movieId) {
        // JOIN FETCH user to avoid LazyInitializationException on async thread
        Movie movie = movieRepository.findByIdWithUser(movieId)
                .orElseThrow(() -> new IllegalStateException("Movie not found for enrichment: " + movieId));

        String email = movie.getUser().getEmail();
        Map<String, Object> keys = settingsService.getApiKeys(email);
        String tmdbKey = (String) keys.get("tmdb");

        try {
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
                int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
                String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
                String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
                WikipediaResult wiki = wikipediaClient.fetch(origTitle, movieTitle, year);
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

        } catch (Exception e) {
            // TMDB failure (or any unexpected error) -> ERROR status
            log.error("TMDB enrichment failed for movieId={} — setting status=ERROR", movieId, e);
            movie.setStatus(MovieStatus.ERROR);
            movieRepository.save(movie);
        }
    }
}
