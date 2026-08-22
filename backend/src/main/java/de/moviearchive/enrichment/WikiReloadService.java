package de.moviearchive.enrichment;

import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Wikipedia-only retry service. Retries the Wikipedia step for films missing wiki data,
 * without touching TMDB/OMDB data or movie.status (D-01).
 *
 * batchReload(UUID) is fire-and-forget @Async on the dedicated wikiReloadExecutor (D-05),
 * cooldown-window filtered (D-03/D-04), and paced between calls (D-07/D-08). It must never
 * be @Transactional — a long-lived transaction held across Thread.sleep for the full batch
 * duration risks connection-pool exhaustion; only the per-movie retryWikipedia() is
 * @Transactional.
 *
 * Neither retryWikipedia() nor batchReload() is annotated @Retryable — WikipediaClient.fetch()
 * already exhausts a 10-candidate fallback internally; wrapping the caller in Spring-managed
 * retry would re-run that entire cascade on any residual exception, multiplying Wikipedia
 * request volume during exactly the rate-limiting scenario this phase exists to prevent.
 */
@Service
@Slf4j
public class WikiReloadService {

    private final MovieRepository movieRepository;
    private final WikipediaClient wikipediaClient;
    private final IndexingService indexingService;

    @Value("${wiki.retry.cooldown-days:30}")
    private long cooldownDays;

    @Value("${wiki.retry.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    public WikiReloadService(MovieRepository movieRepository,
                             WikipediaClient wikipediaClient,
                             IndexingService indexingService) {
        this.movieRepository = movieRepository;
        this.wikipediaClient = wikipediaClient;
        this.indexingService = indexingService;
    }

    /**
     * Retries the Wikipedia step for a single movie. Plain method — NOT @Async, NOT
     * @Retryable. Sets wikiLastAttemptedAt on EVERY attempt (success or failure) — ENRICH-01.
     * On success, re-indexes the movie into OpenSearch and updates indexed_at (D-02).
     */
    @Transactional
    public void retryWikipedia(Movie movie) {
        movie.setWikiLastAttemptedAt(Instant.now());
        try {
            int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
            String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
            String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
            WikipediaResult wiki = wikipediaClient.fetch(origTitle, movieTitle, year);
            movie.setWikiUrl(wiki.url());
            movie.setWikiSummary(wiki.summary());
            movie.setWikiPlot(wiki.plot());
            movie.setWikiCritics(wiki.critics());
            movieRepository.save(movie);
            log.info("Wiki retry succeeded movieId={}", movie.getId());

            // D-02: re-index on late success, same silent-fail pattern as EnrichmentService Step 5
            try {
                indexingService.index(movie);
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
            } catch (Exception e) {
                log.warn("Wiki retry: OpenSearch re-index failed movieId={}: {}",
                        movie.getId(), e.getMessage());
            }
        } catch (WikipediaNotFoundException e) {
            movieRepository.save(movie);
            log.warn("Wiki retry: still not found movieId={}", movie.getId());
        } catch (Exception e) {
            movieRepository.save(movie);
            log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
        }
    }

    /**
     * Batch-retries Wikipedia for all of a user's cooldown-eligible movies missing wiki
     * data. Fire-and-forget (D-05) on the dedicated wikiReloadExecutor bean — the endpoint
     * caller returns immediately. Paces a Thread.sleep(pacingDelayMs) between consecutive
     * Wikipedia calls, but never after the last eligible film, and performs zero sleeps
     * when 0 or 1 films are eligible (ENRICH-03). NOT @Transactional — see class javadoc.
     * A single per-movie failure never aborts processing of the remaining eligible movies.
     */
    @Async("wikiReloadExecutor")
    public void batchReload(UUID userId) {
        Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
        List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
        log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

        for (int i = 0; i < eligible.size(); i++) {
            Movie movie = eligible.get(i);
            try {
                retryWikipedia(movie);
            } catch (Exception e) {
                log.warn("Wiki batch-reload: unexpected error for movieId={}: {}",
                        movie.getId(), e.getMessage());
            }
            if (i < eligible.size() - 1) {
                try {
                    Thread.sleep(pacingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Wiki batch-reload interrupted for userId={} at index={}", userId, i);
                    return;
                }
            }
        }
        log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
    }
}
