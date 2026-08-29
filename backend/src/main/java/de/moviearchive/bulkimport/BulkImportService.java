package de.moviearchive.bulkimport;

import de.moviearchive.bulkimport.ImportLineParser.ParsedLine;
import de.moviearchive.bulkimport.dto.MatchedLine;
import de.moviearchive.bulkimport.dto.ResolveLineRequest;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.enrichment.TmdbClient;
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
import java.util.List;
import java.util.NoSuchElementException;
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
                             ImportLineParser importLineParser,
                             BulkImportBatchRepository bulkImportBatchRepository,
                             BulkImportProgressService progressService,
                             @Lazy BulkImportService self) {
        this.bulkImportLineRepository = bulkImportLineRepository;
        this.userRepository = userRepository;
        this.tmdbClient = tmdbClient;
        this.movieService = movieService;
        this.enrichmentService = enrichmentService;
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
    public void runImport(
            String email, String tmdbKey, List<String> rawLines, UUID batchId, boolean isCsvFormat) {
        log.info("Bulk import starting email={} lines={} batchId={}", email, rawLines.size(), batchId);

        // Pass 1: match + save every line. Bulk-imported movies skip the Wikipedia step
        // entirely (Pass 2 below), so there is no longer any need to pre-resolve each line's
        // imdbId up front before Pass 2 fires.
        List<UUID> matchedMovieIds = new ArrayList<>();
        for (int i = 0; i < rawLines.size(); i++) {
            try {
                // CR-01: processLine()'s @Transactional method returns before we get here, so
                // its transaction has already committed — safe to fire the TMDB detail call
                // now. Calling it from inside processLine() (while its own transaction is still
                // open) raced against the not-yet-committed INSERT.
                self.processLine(email, tmdbKey, rawLines.get(i), batchId, isCsvFormat)
                        .ifPresent(matched -> matchedMovieIds.add(matched.movieId()));
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

        // Pass 2: enrich every matched line with skipWikipedia=true — bulk-imported movies get
        // TMDB + OMDB data only here; WikiReloadService.batchReload() backfills their Wikipedia
        // data later, paced and outside this synchronous dispatch loop. Paced like Pass 1 (and
        // wrapped per-call) so dispatch never bursts past enrichmentExecutor's bounded capacity
        // (core=2/max=5/queue=50) — an unpaced tight loop over a realistic match count throws
        // an uncaught rejection that aborts the batch and skips progressService.complete().
        for (int i = 0; i < matchedMovieIds.size(); i++) {
            UUID movieId = matchedMovieIds.get(i);
            try {
                enrichmentService.enrich(movieId, true);
            } catch (Exception e) {
                log.warn("Bulk import: enrichment dispatch failed for movieId={}: {}", movieId, e.getMessage());
            }
            if (i < matchedMovieIds.size() - 1) {
                try {
                    Thread.sleep(pacingDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Bulk import interrupted for email={} during Pass 2 at index={}", email, i);
                    return;
                }
            }
        }

        progressService.complete(batchId);
        log.info("Bulk import complete email={} processed={}", email, rawLines.size());
    }

    /**
     * D-08/D-09/D-10: resolves an AMBIGUOUS/NOT_FOUND line to a manually-picked TMDB
     * candidate — saves the movie via the existing idempotent pipeline and updates THIS
     * specific line row to SAVED so the batch report reflects the resolution. lineId is
     * looked up scoped to batchId (T-15-01's IDOR mitigation) — a lineId belonging to a
     * different batch (even one owned by the same user) throws NoSuchElementException, which
     * the controller's existing handleNotFound() turns into 404.
     *
     * Does NOT call enrichmentService.enrich() here (CR-01 — see MovieController.saveMovie()
     * and processLine()/runImport() above for the established sequencing this mirrors): the
     * caller must fire enrichment only after this method's transaction has committed.
     */
    @Transactional
    public MovieInitiateResult resolveLine(String email, UUID batchId, UUID lineId, ResolveLineRequest request) {
        BulkImportLine line = bulkImportLineRepository.findByIdAndBatchId(lineId, batchId)
                .orElseThrow(() -> new NoSuchElementException("Line not found: " + lineId));

        MovieInitiateResult result = movieService.initiate(email, request.tmdbId());

        line.setStatus(BulkImportLineStatus.SAVED);
        line.setTmdbId(request.tmdbId());
        line.setPosterPath(request.posterPath());
        line.setUpdatedAt(Instant.now());
        bulkImportLineRepository.save(line);

        return result;
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
    public Optional<MatchedLine> processLine(
            String email, String tmdbKey, String rawLine, UUID batchId, boolean isCsvFormat) {
        ParsedLine parsed = isCsvFormat ? importLineParser.parseCsv(rawLine) : importLineParser.parse(rawLine);
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

        // D-08/D-10: skip entirely (no TMDB call, no write) if already SAVED IN THIS BATCH.
        // CR-01/D-02/D-03: batch-scoped — a title/year SAVED in a DIFFERENT (older) batch must
        // fall through to the full match+save pipeline below, since each batch is an independent
        // snapshot. movieService.initiate()'s existing tmdbId-idempotency (unchanged) guarantees
        // no duplicate Movie row when this batch's line lands on the same TMDB match.
        Optional<BulkImportLine> existingSaved = bulkImportLineRepository
                .findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus(
                        user.getId(), batchId, normalizedTitle, parsed.year(), BulkImportLineStatus.SAVED);
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
        BulkImportLine row = findExistingRow(user.getId(), batch.getId(), parsed)
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

    /**
     * CR-01/D-01: batch-scoped — every lookup here is scoped to BOTH userId and batchId (never
     * batchId alone), mirroring the existing findByIdAndBatchId/loadOwnedBatch() defense-in-depth
     * convention, so a re-upload only ever finds/updates a row belonging to THIS batch. Without
     * this scoping, an overlapping title/year across two different batches silently reassigned
     * an older batch's row to the new batch (CR-01, 15-REVIEW.md).
     */
    private Optional<BulkImportLine> findExistingRow(UUID userId, UUID batchId, ParsedLine parsed) {
        if (parsed.year() != null) {
            Optional<BulkImportLine> byTitleAndYear = bulkImportLineRepository
                    .findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                            userId, batchId, normalize(parsed.title()), parsed.year());
            if (byTitleAndYear.isPresent()) {
                return byTitleAndYear;
            }
            // WR-03: this line now parses, but it may previously have been a PARSE_ERROR row
            // (always persisted with year=null) sharing the same title — probe for it so the
            // re-upload updates that row in place instead of orphaning it as a duplicate.
            return bulkImportLineRepository.findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull(
                    userId, batchId, normalize(parsed.title()));
        }
        return bulkImportLineRepository.findByUserIdAndBatchIdAndRawLineAndYearIsNull(
                userId, batchId, parsed.rawLine());
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
