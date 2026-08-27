package de.moviearchive.enrichment;

import de.moviearchive.admin.WikiReloadProgressService;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
    private final WikiReloadProgressService progressService;
    private final WikiReloadService self;

    @Value("${wiki.retry.cooldown-days:30}")
    private long cooldownDays;

    @Value("${wiki.retry.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    /** Outcome of a single doRetryWikipedia() attempt — D-03/D-14-02: only SUCCESS and
     * NOT_FOUND are genuine, successfully-executed attempts that advance the cooldown
     * timestamp; FAILED is a technical/rate-limit failure that must NOT cooldown-block the
     * movie for the next batch-reload run. */
    public enum WikiRetryOutcome { SUCCESS, NOT_FOUND, FAILED }

    /**
     * {@code self} is a lazily-resolved reference to this bean's own Spring proxy. Calling
     * {@code self.retryWikipedia(...)} instead of {@code this.retryWikipedia(...)} from
     * batchReload() routes the call through the proxy so @Transactional actually applies —
     * a same-class unqualified call bypasses Spring AOP entirely (see class javadoc and
     * this project's CLAUDE.md note on @Async/@Retryable self-invocation, which applies
     * identically to @Transactional). @Lazy avoids a circular-init failure since the proxy
     * wraps this same bean.
     */
    public WikiReloadService(MovieRepository movieRepository,
                             WikipediaClient wikipediaClient,
                             IndexingService indexingService,
                             WikiReloadProgressService progressService,
                             @Lazy WikiReloadService self) {
        this.movieRepository = movieRepository;
        this.wikipediaClient = wikipediaClient;
        this.indexingService = indexingService;
        this.progressService = progressService;
        this.self = self;
    }

    /**
     * Retries the Wikipedia step for a single movie. Plain method — NOT @Async, NOT
     * @Retryable. Sets wikiLastAttemptedAt only on a genuine, successfully-executed attempt
     * (success or confirmed WikipediaNotFoundException) — D-03/D-14-02; a technical/rate-limit
     * failure leaves it unchanged so the movie stays eligible for the very next reload. On
     * success, re-indexes the movie into OpenSearch and updates indexed_at (D-02). Resolves
     * Wikidata via the internal single-ID SPARQL path — no batch prefetch map.
     */
    @Transactional
    public void retryWikipedia(Movie movie) {
        doRetryWikipedia(movie, null);
    }

    /**
     * Same as {@link #retryWikipedia(Movie)}, but accepts a batch-prefetched map of IMDb ID
     * -> enwiki article title (built once per {@link #batchReload(UUID)} invocation, before its
     * per-movie loop starts) so this movie's Wikidata resolution skips its own per-movie SPARQL
     * call entirely — see {@link WikipediaClient#fetch(String, String, int, String, Map)}.
     * Returns the {@link WikiRetryOutcome} so batchReload() can publish an accurate
     * SUCCESS/NOT_FOUND/FAILED status per movie (D-14-03). This overload is only ever called
     * internally by batchReload() — no external caller depends on its return type.
     */
    @Transactional
    public WikiRetryOutcome retryWikipedia(Movie movie, Map<String, String> preResolvedTitles) {
        return doRetryWikipedia(movie, preResolvedTitles);
    }

    private WikiRetryOutcome doRetryWikipedia(Movie movie, Map<String, String> preResolvedTitles) {
        try {
            int year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : 0;
            String origTitle = movie.getOriginalTitle() != null ? movie.getOriginalTitle() : movie.getTitle();
            String movieTitle = movie.getTitle() != null ? movie.getTitle() : "";
            WikipediaResult wiki = preResolvedTitles != null
                    ? wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId(), preResolvedTitles)
                    : wikipediaClient.fetch(origTitle, movieTitle, year, movie.getImdbId());
            movie.setWikiUrl(wiki.url());
            movie.setWikiSummary(wiki.summary());
            movie.setWikiPlot(wiki.plot());
            movie.setWikiCritics(wiki.critics());
            movie.setWikiLastAttemptedAt(Instant.now());
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
            return WikiRetryOutcome.SUCCESS;
        } catch (WikipediaNotFoundException e) {
            // Genuine result: the fallback cascade was fully exhausted with no hit — still
            // advances the cooldown timestamp (D-03).
            movie.setWikiLastAttemptedAt(Instant.now());
            movieRepository.save(movie);
            log.warn("Wiki retry: still not found movieId={}", movie.getId());
            return WikiRetryOutcome.NOT_FOUND;
        } catch (Exception e) {
            // Technical/rate-limit failure: wikiLastAttemptedAt intentionally left unchanged
            // (D-03) — this movie remains immediately eligible for the very next reload run.
            movieRepository.save(movie);
            log.warn("Wiki retry failed movieId={}: {}", movie.getId(), e.getMessage());
            return WikiRetryOutcome.FAILED;
        }
    }

    /**
     * Batch-retries Wikipedia for all of a user's cooldown-eligible movies missing wiki
     * data. Fire-and-forget (D-05) on the dedicated wikiReloadExecutor bean — the endpoint
     * caller returns immediately. Paces a Thread.sleep(pacingDelayMs) between consecutive
     * Wikipedia calls, but never after the last eligible film, and performs zero sleeps
     * when 0 or 1 films are eligible (ENRICH-03). NOT @Transactional — see class javadoc.
     * A single per-movie failure never aborts processing of the remaining eligible movies.
     *
     * Resolves Wikidata one {@link WikipediaClient#SPARQL_CHUNK_SIZE}-sized chunk at a time,
     * interleaved with processing that chunk's movies — NOT the whole eligible set resolved
     * upfront in one back-to-back burst of SPARQL requests. Live UAT of this phase showed the
     * old upfront-prefetch-everything approach fires ALL SPARQL chunk requests (e.g. 8 of them
     * for 382 movies) within seconds of each other, tripping Wikidata's rate limiter almost
     * immediately and burning through the shared 429 backoff before a single movie is even
     * processed. Interleaving spreads each chunk's SPARQL request out by however long the
     * PREVIOUS chunk's movies took to process (each paced at pacingDelayMs), which is exactly
     * the kind of spacing the deliberate pacing this phase introduces (D-14-01) is meant to
     * provide — the prefetch step just wasn't sharing in it before.
     */
    @Async("wikiReloadExecutor")
    public void batchReload(UUID userId) {
        // D-14-04/Pitfall 4: reset the stop flag at the very top, before the per-movie loop,
        // so a fresh Start after a prior Stop never inherits a stale true flag.
        progressService.resetRun(userId);

        // CR-01: the whole body is wrapped in try/finally so progressService.complete(userId)
        // is GUARANTEED to run even if something before or during the loop throws (e.g. a
        // transient DB error from findEligibleForWikiReload) — otherwise the SSE stream hangs
        // forever for this user and the stopFlags/durationWindowsMs entries leak permanently,
        // since complete() is the only code path that clears them.
        int eligibleCount = 0;
        int processedCount = 0;
        try {
            Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
            List<Movie> eligible = movieRepository.findEligibleForWikiReload(userId, cutoff);
            eligibleCount = eligible.size();
            log.info("Wiki batch-reload starting userId={} eligible={}", userId, eligibleCount);

            if (eligibleCount > 0) {
                // Publish a zero-progress "started" event now, before the first chunk's Wikidata
                // prefetch below — otherwise the frontend gets no progress signal (and shows no
                // Stop button / progress panel) until the first movie is processed, which can be
                // delayed if that first chunk's prefetch hits a rate-limit backoff.
                progressService.start(userId, eligibleCount);
            }

            chunks:
            for (List<Movie> movieChunk : WikipediaClient.chunk(eligible, WikipediaClient.SPARQL_CHUNK_SIZE)) {
                // D-08: also checked BEFORE each chunk's own SPARQL request — a Stop click
                // between chunks must not fire one more prefetch call it doesn't need.
                if (progressService.isStopRequested(userId)) {
                    log.info("Wiki batch-reload stopped userId={} before next chunk", userId);
                    break;
                }

                // D-02: resolve Wikidata for THIS chunk only, immediately before processing it —
                // see method javadoc for why this replaced resolving the entire eligible set
                // upfront in one burst of back-to-back SPARQL requests.
                List<String> chunkImdbIds = movieChunk.stream()
                        .map(Movie::getImdbId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
                Map<String, String> resolvedTitles = wikipediaClient.resolveViaWikidataSparql(chunkImdbIds);

                for (Movie movie : movieChunk) {
                    // D-08: checked between movies, never mid-fetch — a Stop click halts cleanly here.
                    if (progressService.isStopRequested(userId)) {
                        log.info("Wiki batch-reload stopped userId={} at processedCount={}", userId, processedCount);
                        break chunks;
                    }
                    String status;
                    long startMs = System.currentTimeMillis();
                    try {
                        WikiRetryOutcome outcome = self.retryWikipedia(movie, resolvedTitles);
                        status = outcome.name();
                    } catch (Exception e) {
                        log.warn("Wiki batch-reload: unexpected error for movieId={}: {}",
                                movie.getId(), e.getMessage());
                        status = WikiRetryOutcome.FAILED.name();
                    }
                    long callDurationMs = System.currentTimeMillis() - startMs;
                    // ETA bug found live in UAT (2026-08-27, 382-movie run): publishing only the
                    // raw fetch-call duration (typically well under 1s) massively understated the
                    // real per-movie cadence, since every movie but the last is ALSO followed by
                    // a pacingDelayMs sleep (30s by default) that the rolling window never saw —
                    // an 8-of-380 run showed a ~40min ETA when the real pace implied ~190min+.
                    // Adding pacingDelayMs here reflects the actual full per-movie cycle time
                    // this loop imposes; it's a no-op for ETA purposes on the true last movie
                    // (processed == eligibleCount makes the "remaining" factor zero anyway) and
                    // still grows correctly under an active 429 backoff, since that wait happens
                    // synchronously inside callDurationMs itself (D-07).
                    long durationMs = callDurationMs + pacingDelayMs;
                    processedCount++;
                    progressService.publish(userId, processedCount, eligibleCount, movie.getTitle(), status, durationMs);

                    if (processedCount < eligibleCount) {
                        // A Stop click during the pacing window shouldn't wait out the full delay.
                        if (progressService.isStopRequested(userId)) {
                            log.info("Wiki batch-reload stopped userId={} before pacing at processedCount={}",
                                    userId, processedCount);
                            break chunks;
                        }
                        try {
                            Thread.sleep(pacingDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Wiki batch-reload interrupted for userId={} at processedCount={}",
                                    userId, processedCount);
                            break chunks;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Wiki batch-reload: fatal error before/outside per-movie loop for userId={}: {}",
                    userId, e.getMessage(), e);
        } finally {
            progressService.complete(userId);
        }
        log.info("Wiki batch-reload complete userId={} processed={} eligible={}",
                userId, processedCount, eligibleCount);
    }
}
