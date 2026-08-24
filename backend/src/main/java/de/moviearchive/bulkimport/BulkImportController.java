package de.moviearchive.bulkimport;

import de.moviearchive.movie.MovieService;
import de.moviearchive.movie.NoTmdbKeyException;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final BulkImportProgressService progressService;

    // WR-02: the global bulkImportExecutor is sized to a single running + single queued slot
    // (AsyncConfig.java), so an unbounded upload can tie up the only import slot for hours at
    // the default pacing delay. Fail fast with 400 before dispatching the async job.
    @Value("${bulk-import.max-lines:5000}")
    private int maxLines;

    public BulkImportController(
            BulkImportService bulkImportService, MovieService movieService, ImportLineParser importLineParser,
            UserRepository userRepository, BulkImportBatchRepository bulkImportBatchRepository,
            BulkImportProgressService progressService) {
        this.bulkImportService = bulkImportService;
        this.movieService = movieService;
        this.importLineParser = importLineParser;
        this.userRepository = userRepository;
        this.bulkImportBatchRepository = bulkImportBatchRepository;
        this.progressService = progressService;
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
