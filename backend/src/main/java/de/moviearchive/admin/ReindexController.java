package de.moviearchive.admin;

import de.moviearchive.indexing.IndexingService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/reindex")
@Slf4j
public class ReindexController {

    private final IndexingService indexingService;
    private final UserRepository userRepository;

    public ReindexController(IndexingService indexingService, UserRepository userRepository) {
        this.indexingService = indexingService;
        this.userRepository = userRepository;
    }

    /**
     * POST /admin/reindex/{userId}
     * Full reindex: deletes the existing index, recreates it, and reindexes all movies for the user.
     * Returns {indexed: N} with the count of movies processed.
     * Returns 403 if the path userId does not belong to the authenticated JWT subject (D-03 ownership check).
     */
    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> fullReindex(
            @PathVariable UUID userId,
            Authentication auth) throws IOException {
        assertOwnership(auth, userId);
        log.info("Full reindex requested for userId={}", userId);
        int count = indexingService.fullReindex(userId);
        log.info("Full reindex complete for userId={} indexed={}", userId, count);
        return ResponseEntity.ok(Map.of("indexed", count));
    }

    /**
     * POST /admin/reindex/{userId}/pending
     * Partial reindex: indexes only movies with indexed_at IS NULL for the user.
     * Returns {indexed: N} with the count of movies indexed.
     * Returns 403 if the path userId does not belong to the authenticated JWT subject (D-03 ownership check).
     */
    @PostMapping("/{userId}/pending")
    public ResponseEntity<Map<String, Object>> pendingReindex(
            @PathVariable UUID userId,
            Authentication auth) throws IOException {
        assertOwnership(auth, userId);
        log.info("Pending reindex requested for userId={}", userId);
        int count = indexingService.reindexPending(userId);
        log.info("Pending reindex complete for userId={} indexed={}", userId, count);
        return ResponseEntity.ok(Map.of("indexed", count));
    }

    /**
     * Resolves the authenticated user from the JWT subject (email), looks up their userId,
     * and asserts it matches the path userId. Throws AccessDeniedException on mismatch (D-03).
     */
    private void assertOwnership(Authentication auth, UUID userId) {
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        if (!user.getId().equals(userId)) {
            throw new AccessDeniedException("Access denied.");
        }
    }

    // --- Exception handlers ---

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
    }
}
