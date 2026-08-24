package de.moviearchive.bulkimport;

import de.moviearchive.bulkimport.ImportLineParser.ParsedLine;
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
import java.util.List;
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
    private final BulkImportService self;

    @Value("${bulk-import.pacing-delay-ms:1000}")
    private long pacingDelayMs;

    public BulkImportService(BulkImportLineRepository bulkImportLineRepository,
                             UserRepository userRepository,
                             TmdbClient tmdbClient,
                             MovieService movieService,
                             EnrichmentService enrichmentService,
                             ImportLineParser importLineParser,
                             @Lazy BulkImportService self) {
        this.bulkImportLineRepository = bulkImportLineRepository;
        this.userRepository = userRepository;
        this.tmdbClient = tmdbClient;
        this.movieService = movieService;
        this.enrichmentService = enrichmentService;
        this.importLineParser = importLineParser;
        this.self = self;
    }

    /**
     * Fire-and-forget batch job on the dedicated bulkImportExecutor bean. Processes each raw
     * line in order, isolating per-line failures (a single bad line never aborts the rest of
     * the batch), pacing Thread.sleep(pacingDelayMs) between lines (never after the last).
     */
    @Async("bulkImportExecutor")
    public void runImport(String email, String tmdbKey, List<String> rawLines) {
        log.info("Bulk import starting email={} lines={}", email, rawLines.size());
        for (int i = 0; i < rawLines.size(); i++) {
            try {
                self.processLine(email, tmdbKey, rawLines.get(i));
            } catch (Exception e) {
                log.warn("Bulk import: unexpected error for line index={}: {}", i, e.getMessage());
            }
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
        log.info("Bulk import complete email={} processed={}", email, rawLines.size());
    }

    /**
     * Processes a single raw line: parse -> dedup-check -> TMDB search -> match -> upsert.
     * @Transactional — routed through the self-proxy from runImport().
     */
    @Transactional
    public void processLine(String email, String tmdbKey, String rawLine) {
        ParsedLine parsed = importLineParser.parse(rawLine);
        if (parsed == null) {
            // D-02: blank line — skip silently, nothing persisted.
            return;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        if (!parsed.valid()) {
            upsertLine(user, parsed, BulkImportLineStatus.PARSE_ERROR, null);
            return;
        }

        String normalizedTitle = normalize(parsed.title());

        // D-08/D-10: skip entirely (no TMDB call, no write) if already SAVED.
        Optional<BulkImportLine> existingSaved = bulkImportLineRepository
                .findByUserIdAndNormalizedTitleAndYearAndStatus(
                        user.getId(), normalizedTitle, parsed.year(), BulkImportLineStatus.SAVED);
        if (existingSaved.isPresent()) {
            log.info("Bulk import: skipping already-saved line title={} year={}", parsed.title(), parsed.year());
            return;
        }

        List<TmdbSearchResultItem> results = tmdbClient.search(parsed.title(), tmdbKey);
        List<TmdbSearchResultItem> yearMatches = results.stream()
                .filter(r -> r.year() != null && r.year().equals(parsed.year()))
                .toList();

        if (yearMatches.isEmpty()) {
            upsertLine(user, parsed, BulkImportLineStatus.NOT_FOUND, null);
            return;
        }

        if (yearMatches.size() == 1) {
            saveAndUpsert(user, email, parsed, yearMatches.get(0).tmdbId());
            return;
        }

        // D-06: still ambiguous after year filter — try original-title narrowing.
        if (parsed.originalTitle() != null && !parsed.originalTitle().isBlank()) {
            List<TmdbSearchResultItem> narrowed = yearMatches.stream()
                    .filter(r -> r.originalTitle() != null
                            && r.originalTitle().equalsIgnoreCase(parsed.originalTitle()))
                    .toList();
            if (narrowed.size() == 1) {
                saveAndUpsert(user, email, parsed, narrowed.get(0).tmdbId());
                return;
            }
        }

        // D-04: multiple candidates, no unambiguous narrowing — never auto-guess.
        upsertLine(user, parsed, BulkImportLineStatus.AMBIGUOUS, null);
    }

    /**
     * Saves a matched line through the existing idempotent save+enrich pipeline — exactly
     * MovieController.saveMovie()'s sequence (D-12): initiate() then enrich() only if new.
     */
    private void saveAndUpsert(User user, String email, ParsedLine parsed, int tmdbId) {
        MovieInitiateResult result = movieService.initiate(email, tmdbId);
        if (result.isNew()) {
            enrichmentService.enrich(result.id());
        }
        upsertLine(user, parsed, BulkImportLineStatus.SAVED, tmdbId);
    }

    /**
     * Finds the existing row for this line (across any prior status) or creates a new one,
     * then updates it in place — never inserts a duplicate row per re-upload (D-13).
     */
    private void upsertLine(User user, ParsedLine parsed, BulkImportLineStatus status, Integer tmdbId) {
        BulkImportLine row = findExistingRow(user.getId(), parsed)
                .orElseGet(() -> new BulkImportLine(user, parsed.rawLine()));
        row.setTitle(cap(parsed.title()));
        row.setOriginalTitle(cap(parsed.originalTitle()));
        row.setYear(parsed.year());
        row.setStatus(status);
        row.setTmdbId(tmdbId);
        row.setUpdatedAt(Instant.now());
        bulkImportLineRepository.save(row);
        log.info("Bulk import: upserted line title={} year={} status={}", parsed.title(), parsed.year(), status);
    }

    private Optional<BulkImportLine> findExistingRow(UUID userId, ParsedLine parsed) {
        if (parsed.year() != null) {
            return bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYear(
                    userId, normalize(parsed.title()), parsed.year());
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
