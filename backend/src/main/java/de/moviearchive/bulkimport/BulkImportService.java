package de.moviearchive.bulkimport;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.bulkimport.ImportLineParser.ParsedLine;
import de.moviearchive.bulkimport.dto.MatchedLine;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.enrichment.WikipediaClient;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieService;
import de.moviearchive.movie.dto.MovieInitiateResult;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Async bulk-import batch job. Mirrors WikiReloadService's structure: a @Lazy self-proxy
 * routes per-line calls through Spring's proxy so @Transactional actually applies (same-class
 * unqualified calls bypass Spring AOP — see WikiReloadService javadoc and this project's
 * CLAUDE.md), per-line failure isolation (one bad line never aborts the batch, D-03), and
 * Thread.sleep pacing between lines (D-11).
 */
@Service
@Slf4j
public class BulkImportService {

    private final BulkImportLineRepository bulkImportLineRepository;
    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final MovieService movieService;
    private final EnrichmentService enrichmentService;
    private final MovieRepository movieRepository;
    private final WikipediaClient wikipediaClient;
    private final ImportLineParser importLineParser;
    private final BulkImportBatchRepository bulkImportBatchRepository;
    private final BulkImportProgressService progressService;
    private final BulkImportService self;

    @Value("${bulk-import.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    public BulkImportService(BulkImportLineRepository bulkImportLineRepository,
                             UserRepository userRepository,
                             TmdbClient tmdbClient,
                             MovieService movieService,
                             EnrichmentService enrichmentService,
                             MovieRepository movieRepository,
                             WikipediaClient wikipediaClient,
                             ImportLineParser importLineParser,
                             BulkImportBatchRepository bulkImportBatchRepository,
                             BulkImportProgressService progressService,
                             @Lazy BulkImportService self) {
        this.bulkImportLineRepository = bulkImportLineRepository;
        this.userRepository = userRepository;
        this.tmdbClient = tmdbClient;
        this.movieService = movieService;
        this.enrichmentService = enrichmentService;
        this.movieRepository = movieRepository;
        this.wikipediaClient = wikipediaClient;
        this.importLineParser = importLineParser;
        this.bulkImportBatchRepository = bulkImportBatchRepository;
        this.progressService = progressService;
        this.self = self;
    }

    /**
     * Creates and persists a durable batch record synchronously — the caller (the controller,
     * before dispatching the async job) already knows the total submitted line count. D-02:
     * this is the foundation every later plan in this phase reads from (SSE progress totals,
     * batch list/detail endpoints).
     */
    public BulkImportBatch createBatch(String email, int totalLines) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return bulkImportBatchRepository.save(new BulkImportBatch(user, totalLines));
    }

    /**
     * Fire-and-forget batch job on the dedicated bulkImportExecutor bean. Processes each raw
     * line in order, isolating per-line failures (a single bad line never aborts the rest of
     * the batch), pacing Thread.sleep(pacingDelayMs) between lines (never after the last).
     */
    @Async("bulkImportExecutor")
    public void runImport(String email, String tmdbKey, List<String> rawLines, UUID batchId) {
        log.info("Bulk import starting email={} lines={} batchId={}", email, rawLines.size(), batchId);

        // Pass 1: match + save every line, resolving each newly-matched line's TMDB detail
        // (and therefore imdbId) up front — before any Wikipedia lookup fires. Unlike
        // batch-reload (where movie.imdbId is already persisted from a prior save),
        // bulk-import's imdbId is otherwise only discovered inside enrichmentService.enrich()'s
        // own TMDB detail call, one movie at a time (D-03).
        List<UUID> matchedMovieIds = new ArrayList<>();
        Map<UUID, String> imdbIdByMovieId = new HashMap<>();
        for (int i = 0; i < rawLines.size(); i++) {
            try {
                // CR-01: processLine()'s @Transactional method returns before we get here, so
                // its transaction has already committed — safe to fire the TMDB detail call
                // now. Calling it from inside processLine() (while its own transaction is still
                // open) raced against the not-yet-committed INSERT.
                self.processLine(email, tmdbKey, rawLines.get(i), batchId).ifPresent(matched -> {
                    matchedMovieIds.add(matched.movieId());
                    String imdbId = self.resolveAndPersistImdbId(matched.movieId(), matched.tmdbId(), tmdbKey);
                    if (imdbId != null) {
                        imdbIdByMovieId.put(matched.movieId(), imdbId);
                    }
                });
            } catch (Exception e) {
                log.warn("Bulk import: unexpected error for line index={}: {}", i, e.getMessage());
            }
            progressService.publish(batchId, i + 1, rawLines.size());
            if (i < rawLines.size() - 1) {
                try {
                    Thread.sleep(pacingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Bulk import interrupted for email={} at index={}", email, i);
                    return;
                }
            }
        }

        // Pass 1.5: one batched SPARQL call for every imdbId collected in this run (D-03) —
        // NOT one call per line, which would only swap the endpoint without reducing request
        // count.
        List<String> imdbIds = List.copyOf(imdbIdByMovieId.values());
        Map<String, String> resolvedTitles = wikipediaClient.resolveViaWikidataSparql(imdbIds);

        // Pass 2: enrich every matched line, threading the SAME resolved map into each call so
        // the Wikipedia step skips a per-movie SPARQL call entirely.
        for (UUID movieId : matchedMovieIds) {
            enrichmentService.enrich(movieId, resolvedTitles);
        }

        progressService.complete(batchId);
        log.info("Bulk import complete email={} processed={}", email, rawLines.size());
    }

    /**
     * Fetches TMDB detail for a newly-matched line and persists its imdbId onto the Movie row
     * immediately, so Pass 1.5's batched SPARQL call has every matched line's imdbId available
     * up front (D-03, Pitfall 2: bulk-import's imdbId is otherwise only discovered later, one
     * movie at a time, inside EnrichmentService.enrich()'s own TMDB detail call). Persisting it
     * here makes enrich()'s own later detail call redundant-but-harmless. Never throws —
     * matches this codebase's established swallow-and-degrade convention for external-API call
     * sites; returns null on any failure.
     */
    @Transactional
    public String resolveAndPersistImdbId(UUID movieId, int tmdbId, String tmdbKey) {
        try {
            JsonNode tmdbDetail = tmdbClient.fetchDetail(tmdbId, tmdbKey);
            String extractedImdbId = tmdbDetail.path("external_ids").path("imdb_id").asText(null);
            if (extractedImdbId != null && extractedImdbId.isBlank()) {
                extractedImdbId = null;
            }
            final String imdbId = extractedImdbId;
            if (imdbId != null) {
                movieRepository.findById(movieId).ifPresent(movie -> {
                    movie.setImdbId(imdbId);
                    movieRepository.save(movie);
                });
            }
            return imdbId;
        } catch (Exception e) {
            log.warn("Bulk import: TMDB detail fetch failed for movieId={} tmdbId={}: {}",
                    movieId, tmdbId, e.getMessage());
            return null;
        }
    }

    /**
     * Processes a single raw line: parse -> dedup-check -> TMDB search -> match -> upsert.
     * @Transactional — routed through the self-proxy from runImport().
     *
     * CR-01: returns the id of any newly-created movie so the caller (runImport(), which is
     * NOT @Transactional) can fire enrichmentService.enrich() only after this method's
     * transaction has committed. Calling enrich() (which is @Async and starts running almost
     * immediately on a separate thread/connection) from inside this still-open transaction
     * raced the enrichment thread against the not-yet-committed Movie INSERT under READ
     * COMMITTED isolation, causing "Movie not found for enrichment" and leaving the row stuck
     * at status=PENDING forever.
     */
    @Transactional
    public Optional<MatchedLine> processLine(String email, String tmdbKey, String rawLine, UUID batchId) {
        ParsedLine parsed = importLineParser.parse(rawLine);
        if (parsed == null) {
            // D-02: blank line — skip silently, nothing persisted.
            return Optional.empty();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        // Lazy JPA reference — no extra query, resolved once per line and threaded into every
        // upsertLine()/saveAndUpsert() call below.
        BulkImportBatch batch = bulkImportBatchRepository.getReferenceById(batchId);

        if (!parsed.valid()) {
            upsertLine(user, parsed, BulkImportLineStatus.PARSE_ERROR, null, null, batch);
            return Optional.empty();
        }

        String normalizedTitle = normalize(parsed.title());

        // D-08/D-10: skip entirely (no TMDB call, no write) if already SAVED.
        Optional<BulkImportLine> existingSaved = bulkImportLineRepository
                .findByUserIdAndNormalizedTitleAndYearAndStatus(
                        user.getId(), normalizedTitle, parsed.year(), BulkImportLineStatus.SAVED);
        if (existingSaved.isPresent()) {
            log.info("Bulk import: skipping already-saved line title={} year={}", parsed.title(), parsed.year());
            return Optional.empty();
        }

        List<TmdbSearchResultItem> results;
        try {
            results = tmdbClient.search(parsed.title(), tmdbKey);
        } catch (Exception e) {
            // WR-01: persist a queryable row for every line the user submits, even when the
            // TMDB search itself fails (e.g. @Retryable exhausts its 3 attempts). Reusing
            // NOT_FOUND avoids widening the DB CHECK constraint for a dedicated status while
            // still surfacing the outcome in the per-line audit trail instead of silently
            // dropping the line.
            log.warn("Bulk import: TMDB search failed for title={}: {}", parsed.title(), e.getMessage());
            upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null, null, batch);
            return Optional.empty();
        }
        List<TmdbSearchResultItem> yearMatches = results.stream()
                .filter(r -> r.year() != null && r.year().equals(parsed.year()))
                .toList();

        if (yearMatches.isEmpty()) {
            upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null, null, batch);
            return Optional.empty();
        }

        if (yearMatches.size() == 1) {
            return saveAndUpsert(user, email, parsed, yearMatches.get(0), batch);
        }

        // D-06: still ambiguous after year filter — try original-title narrowing.
        if (parsed.originalTitle() != null && !parsed.originalTitle().isBlank()) {
            List<TmdbSearchResultItem> narrowed = yearMatches.stream()
                    .filter(r -> r.originalTitle() != null
                            && r.originalTitle().equalsIgnoreCase(parsed.originalTitle()))
                    .toList();
            if (narrowed.size() == 1) {
                return saveAndUpsert(user, email, parsed, narrowed.get(0), batch);
            }
        }

        // D-04: multiple candidates, no unambiguous narrowing — never auto-guess.
        upsertLine(user, parsed, BulkImportLineStatus.AMBIGUOUS, null, null, batch);
        return Optional.empty();
    }

    /**
     * Saves a matched line through the existing idempotent save pipeline — exactly
     * MovieController.saveMovie()'s sequence (D-12): initiate(), then upsertLine(). Enrichment
     * is NOT fired here (CR-01) — the id of a newly-created movie is returned so the caller can
     * invoke enrichmentService.enrich() once this method's enclosing transaction has committed.
     */
    private Optional<MatchedLine> saveAndUpsert(
            User user, String email, ParsedLine parsed, TmdbSearchResultItem match, BulkImportBatch batch) {
        MovieInitiateResult result = movieService.initiate(email, match.tmdbId());
        // D-04: match already carries posterPath() from the search results fetched earlier in
        // processLine() — no extra TMDB call needed to capture it here.
        upsertLine(user, parsed, BulkImportLineStatus.SAVED, match.tmdbId(), match.posterPath(), batch);
        return result.isNew() ? Optional.of(new MatchedLine(result.id(), match.tmdbId())) : Optional.empty();
    }

    /**
     * Finds the existing row for this line (across any prior status) or creates a new one,
     * then updates it in place — never inserts a duplicate row per re-upload (D-13).
     */
    private void upsertLine(User user, ParsedLine parsed, BulkImportLineStatus status, Integer tmdbId,
            String posterPath, BulkImportBatch batch) {
        BulkImportLine row = findExistingRow(user.getId(), parsed)
                .orElseGet(() -> new BulkImportLine(user, parsed.rawLine()));
        row.setTitle(cap(parsed.title()));
        row.setOriginalTitle(cap(parsed.originalTitle()));
        row.setYear(parsed.year());
        row.setStatus(status);
        row.setTmdbId(tmdbId);
        row.setPosterPath(posterPath);
        row.setBatch(batch);
        row.setUpdatedAt(Instant.now());
        bulkImportLineRepository.save(row);
        log.info("Bulk import: upserted line title={} year={} status={}", parsed.title(), parsed.year(), status);
    }

    private Optional<BulkImportLine> findExistingRow(UUID userId, ParsedLine parsed) {
        if (parsed.year() != null) {
            Optional<BulkImportLine> byTitleAndYear = bulkImportLineRepository
                    .findByUserIdAndNormalizedTitleAndYear(userId, normalize(parsed.title()), parsed.year());
            if (byTitleAndYear.isPresent()) {
                return byTitleAndYear;
            }
            // WR-03: this line now parses, but it may previously have been a PARSE_ERROR row
            // (always persisted with year=null) sharing the same title — probe for it so the
            // re-upload updates that row in place instead of orphaning it as a duplicate.
            return bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYearIsNull(
                    userId, normalize(parsed.title()));
        }
        return bulkImportLineRepository.findByUserIdAndRawLineAndYearIsNull(userId, parsed.rawLine());
    }

    private String normalize(String title) {
        return title == null ? null : title.trim().toLowerCase();
    }

    /** Truncates to 500 chars to match the VARCHAR(500) column width (V5 Input Validation mitigation). */
    private String cap(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
