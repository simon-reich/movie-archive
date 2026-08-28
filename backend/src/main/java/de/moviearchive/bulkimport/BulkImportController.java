package de.moviearchive.bulkimport;

import de.moviearchive.bulkimport.dto.BulkImportBatchDetail;
import de.moviearchive.bulkimport.dto.BulkImportBatchSummary;
import de.moviearchive.bulkimport.dto.BulkImportLineResult;
import de.moviearchive.bulkimport.dto.ResolveLineRequest;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.movie.MovieService;
import de.moviearchive.movie.NoTmdbKeyException;
import de.moviearchive.movie.dto.MovieInitiateResult;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * POST /movies/bulk-import — multipart upload of a Title;OriginalTitle;Year film list.
 * Returns 202 Accepted immediately; parsing/matching/saving happens in BulkImportService's
 * async job. No IDOR/ownership check is needed on that endpoint — unlike WikiReloadController,
 * it has no path-variable userId at all; the user is resolved exclusively from the JWT subject.
 *
 * GET .../progress DOES take batchId as a path variable, so it ports
 * WikiReloadController.assertOwnership()'s exact pattern via loadOwnedBatch() below (T-11-03).
 */
@RestController
@RequestMapping("/movies")
@Slf4j
public class BulkImportController {

    private final BulkImportService bulkImportService;
    private final MovieService movieService;
    private final ImportLineParser importLineParser;
    private final UserRepository userRepository;
    private final BulkImportBatchRepository bulkImportBatchRepository;
    private final BulkImportLineRepository bulkImportLineRepository;
    private final BulkImportProgressService progressService;
    private final MovieRepository movieRepository;
    private final EnrichmentService enrichmentService;

    // WR-02: the global bulkImportExecutor is sized to a single running + single queued slot
    // (AsyncConfig.java), so an unbounded upload can tie up the only import slot for hours at
    // the default pacing delay. Fail fast with 400 before dispatching the async job.
    @Value("${bulk-import.max-lines:5000}")
    private int maxLines;

    public BulkImportController(
            BulkImportService bulkImportService, MovieService movieService, ImportLineParser importLineParser,
            UserRepository userRepository, BulkImportBatchRepository bulkImportBatchRepository,
            BulkImportLineRepository bulkImportLineRepository, BulkImportProgressService progressService,
            MovieRepository movieRepository, EnrichmentService enrichmentService) {
        this.bulkImportService = bulkImportService;
        this.movieService = movieService;
        this.importLineParser = importLineParser;
        this.userRepository = userRepository;
        this.bulkImportBatchRepository = bulkImportBatchRepository;
        this.bulkImportLineRepository = bulkImportLineRepository;
        this.progressService = progressService;
        this.movieRepository = movieRepository;
        this.enrichmentService = enrichmentService;
    }

    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadBulkImport(
            @RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        String email = auth.getName();
        // Synchronous fail-fast 422 check, BEFORE any file reading or async dispatch.
        String tmdbKey = movieService.resolveTmdbKey(email);

        // MUST read the file's content synchronously, in the request thread — MultipartFile's
        // backing temp storage is cleared once the HTTP request completes (RESEARCH.md Pitfall 1).
        // JDK's new String(bytes, UTF_8) never throws (lenient replacement-character decoding),
        // so non-UTF-8-decodable content simply fails to split into 3 valid fields downstream.
        List<String> rawLines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();

        if (rawLines.size() > maxLines) {
            throw new IllegalArgumentException(
                    "File exceeds " + maxLines + " lines; split into smaller batches.");
        }

        // G-10-1: reject a wholly-unparseable batch synchronously instead of silently
        // starting a no-op async job (D-01's strict format spec). Partial-failure batches
        // (some parseable lines mixed with some invalid ones) are unaffected — they still
        // fall through to bulkImportService.runImport() and hit the per-line PARSE_ERROR
        // persistence path (D-03) exactly as before.
        boolean anyLineParses = rawLines.stream()
                .map(importLineParser::parse)
                .anyMatch(parsed -> parsed != null && parsed.valid());
        if (!anyLineParses) {
            throw new IllegalArgumentException(
                    "No lines could be parsed. Expected format: Title;OriginalTitle;Year per line "
                            + "(Original Title may be left empty), e.g. \"Inception;;2010\". Check your "
                            + "file and try again.");
        }

        BulkImportBatch batch = bulkImportService.createBatch(email, rawLines.size());
        log.info("Bulk import requested email={} lines={} batchId={}", email, rawLines.size(), batch.getId());
        bulkImportService.runImport(email, tmdbKey, rawLines, batch.getId());
        return ResponseEntity.accepted().body(Map.of("status", "started", "batchId", batch.getId().toString()));
    }

    /**
     * GET /movies/bulk-import/{batchId}/progress — Server-Sent Events stream of live import
     * progress (D-01, IMPORT-05). Never accepts a query-param token: this app's JWT scheme is
     * header-only (JwtAuthFilter), and the frontend must consume this via
     * @microsoft/fetch-event-source (not native EventSource) to attach the Authorization header
     * (RESEARCH.md Pitfall 1, T-11-04).
     *
     * SseEmitter(Long.MAX_VALUE) never relies on the container's default async timeout
     * (~10-30s) — a worst-case import at max-lines/pacing-delay-ms can run ~83 minutes
     * (RESEARCH.md Pitfall 2).
     */
    @GetMapping(value = "/bulk-import/{batchId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable UUID batchId, Authentication auth) {
        BulkImportBatch batch = loadOwnedBatch(auth, batchId);
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        progressService.register(batchId, emitter, batch.getTotalLines());
        return emitter;
    }

    /**
     * GET /movies/bulk-import/batches — D-03 batch-list page. No path variable: scoped
     * exclusively by the JWT-resolved user id, never a client-supplied filter (T-11-07).
     */
    @GetMapping("/bulk-import/batches")
    public ResponseEntity<List<BulkImportBatchSummary>> getBatches(Authentication auth) {
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        List<BulkImportBatchSummary> summaries = bulkImportBatchRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(batch -> new BulkImportBatchSummary(
                        batch.getId(), batch.getCreatedAt(), batch.getTotalLines(), statusCounts(batch.getId())))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Converts countByBatchIdGroupByStatus()'s plain Object[] rows (element 0 =
     * BulkImportLineStatus, element 1 = Long) into a Map<String, Long> keyed by status name.
     */
    private Map<String, Long> statusCounts(UUID batchId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : bulkImportLineRepository.countByBatchIdGroupByStatus(batchId)) {
            counts.put(((BulkImportLineStatus) row[0]).name(), (Long) row[1]);
        }
        return counts;
    }

    /**
     * GET /movies/bulk-import/batches/{batchId} — D-03/D-05 batch-detail results view.
     * Ownership-checked via loadOwnedBatch() (T-11-06) — 403 on a batchId owned by another
     * user, 404 on an unknown batchId.
     */
    @GetMapping("/bulk-import/batches/{batchId}")
    public ResponseEntity<BulkImportBatchDetail> getBatchDetail(@PathVariable UUID batchId, Authentication auth) {
        BulkImportBatch batch = loadOwnedBatch(auth, batchId);
        UUID userId = batch.getUser().getId();
        List<BulkImportLineResult> lines = bulkImportLineRepository.findByBatchIdOrderByTitle(batchId)
                .stream()
                .map(line -> {
                    // D-06/D-07: only SAVED lines ever get a movie lookup — every other status
                    // gets movieId=null with zero extra query cost.
                    UUID movieId = (line.getStatus() == BulkImportLineStatus.SAVED && line.getTmdbId() != null)
                            ? movieRepository.findByUserIdAndTmdbId(userId, line.getTmdbId())
                                    .map(Movie::getId)
                                    .orElse(null)
                            : null;
                    return new BulkImportLineResult(
                            line.getId(), line.getTitle(), line.getOriginalTitle(), line.getYear(),
                            line.getStatus().name(), line.getPosterPath(), movieId, line.getRawLine());
                })
                .toList();
        return ResponseEntity.ok(
                new BulkImportBatchDetail(batch.getId(), batch.getCreatedAt(), batch.getTotalLines(), lines));
    }

    /**
     * POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve — D-08/D-09/D-10
     * inline resolution of an AMBIGUOUS/NOT_FOUND line to a manually-picked TMDB candidate.
     * Ownership-scoped on BOTH batchId (loadOwnedBatch(), 403/404) AND lineId
     * (BulkImportService.resolveLine() -> findByIdAndBatchId(), 404 via the existing
     * handleNotFound() handler — T-15-01: a lineId from a different batch, even one owned by
     * the same user, resolves to 404, not merely "line not found in general"). Mirrors
     * MovieController.saveMovie()'s enrich-after-commit sequencing (CR-01) exactly.
     */
    @PostMapping("/bulk-import/batches/{batchId}/lines/{lineId}/resolve")
    public ResponseEntity<Map<String, String>> resolveLine(
            @PathVariable UUID batchId, @PathVariable UUID lineId,
            @Valid @RequestBody ResolveLineRequest request, Authentication auth) {
        loadOwnedBatch(auth, batchId);
        MovieInitiateResult result = bulkImportService.resolveLine(auth.getName(), batchId, lineId, request);
        if (result.isNew()) {
            enrichmentService.enrich(result.id());
        }
        return ResponseEntity.ok(Map.of("movieId", result.id().toString()));
    }

    /**
     * Resolves the authenticated user from the JWT subject (email), loads the batch by id, and
     * asserts it belongs to that user — ports WikiReloadController.assertOwnership()'s exact
     * pattern (T-11-03). Reused as-is by Plan 11-03's batch-detail endpoint.
     */
    private BulkImportBatch loadOwnedBatch(Authentication auth, UUID batchId) {
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        BulkImportBatch batch = bulkImportBatchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + batchId));
        if (!batch.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Access denied.");
        }
        return batch;
    }

    // --- Exception handlers ---

    @ExceptionHandler(NoTmdbKeyException.class)
    public ResponseEntity<Map<String, String>> handleNoTmdbKey(NoTmdbKeyException ex) {
        return ResponseEntity.status(422).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("message", ex.getMessage()));
    }

    /**
     * Fires when bulkImportExecutor's bounded queue (1 running + 1 queued) is already full
     * and a third overlapping trigger is submitted (T-10-06, DoS mitigation).
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleTaskRejected(TaskRejectedException ex) {
        return ResponseEntity.status(503).body(Map.of(
                "message", "A bulk import is already in progress; try again shortly."));
    }

    /** T-11-03: batchId resolves to a batch owned by a different user. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
    }

    /** batchId does not resolve to any persisted batch. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }
}
