package de.moviearchive.enrichment;

import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wikipedia-only retry service (TRACER — Plan 08-01). Retries the Wikipedia step for
 * films missing wiki data, without touching TMDB/OMDB data or movie.status (D-01).
 *
 * batchReload(UUID) is synchronous and unpaced in this tracer plan — Plan 08-02 adds
 * @Async execution on a dedicated bounded executor, cooldown-window eligibility filtering,
 * and pacing between calls.
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
     * Batch-retries Wikipedia for all of a user's movies missing wiki data (wiki_url IS NULL).
     * Plain method — NOT @Async, NOT @Transactional in this tracer plan (synchronous,
     * unpaced by design — Plan 08-02 adds async execution and pacing). A single per-movie
     * failure never aborts processing of the remaining eligible movies.
     */
    public void batchReload(UUID userId) {
        List<Movie> eligible = movieRepository.findByUserIdAndWikiUrlIsNull(userId);
        log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligible.size());

        for (Movie movie : eligible) {
            try {
                retryWikipedia(movie);
            } catch (Exception e) {
                log.warn("Wiki batch-reload: unexpected error for movieId={}: {}",
                        movie.getId(), e.getMessage());
            }
        }
        log.info("Wiki batch-reload complete userId={} processed={}", userId, eligible.size());
    }
}
