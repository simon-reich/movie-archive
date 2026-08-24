package de.moviearchive.bulkimport;

import de.moviearchive.movie.MovieService;
import de.moviearchive.movie.NoTmdbKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * POST /movies/bulk-import — multipart upload of a Title;OriginalTitle;Year film list.
 * Returns 202 Accepted immediately; parsing/matching/saving happens in BulkImportService's
 * async job. No IDOR/ownership check is needed — unlike WikiReloadController, this endpoint
 * has no path-variable userId at all; the user is resolved exclusively from the JWT subject.
 */
@RestController
@RequestMapping("/movies")
@Slf4j
public class BulkImportController {

    private final BulkImportService bulkImportService;
    private final MovieService movieService;
    private final ImportLineParser importLineParser;

    // WR-02: the global bulkImportExecutor is sized to a single running + single queued slot
    // (AsyncConfig.java), so an unbounded upload can tie up the only import slot for hours at
    // the default pacing delay. Fail fast with 400 before dispatching the async job.
    @Value("${bulk-import.max-lines:5000}")
    private int maxLines;

    public BulkImportController(
            BulkImportService bulkImportService, MovieService movieService, ImportLineParser importLineParser) {
        this.bulkImportService = bulkImportService;
        this.movieService = movieService;
        this.importLineParser = importLineParser;
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

        log.info("Bulk import requested email={} lines={}", email, rawLines.size());
        bulkImportService.runImport(email, tmdbKey, rawLines);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
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
}
