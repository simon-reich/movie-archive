package de.moviearchive.admin;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * POST /admin/wiki-reload/{userId} triggers a fire-and-forget Wikipedia-only batch retry
 * (D-05) for the user's cooldown-eligible films missing wiki data. Returns 202 Accepted
 * immediately; the batch runs async on the dedicated wikiReloadExecutor. A third
 * overlapping trigger (beyond 1 running + 1 queued) is rejected with 503 (T-08-02).
 *
 * No hasRole("ADMIN") check — no such role/authority exists anywhere in this codebase's
 * SecurityConfig or User entity; "admin" endpoints here mean ownership-checked +
 * authenticated, same convention as ReindexController.
 */
@RestController
@RequestMapping("/admin/wiki-reload")
@Slf4j
public class WikiReloadController {

    private final WikiReloadService wikiReloadService;
    private final UserRepository userRepository;

    public WikiReloadController(WikiReloadService wikiReloadService, UserRepository userRepository) {
        this.wikiReloadService = wikiReloadService;
        this.userRepository = userRepository;
    }

    /**
     * POST /admin/wiki-reload/{userId}
     * Retries the Wikipedia step for all of the user's movies missing wiki data.
     * Returns 403 if the path userId does not belong to the authenticated JWT subject
     * (IDOR protection, T-08-01).
     */
    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, String>> triggerReload(
            @PathVariable UUID userId, Authentication auth) {
        assertOwnership(auth, userId);
        log.info("Wiki batch-reload requested for userId={}", userId);
        wikiReloadService.batchReload(userId);
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    /**
     * Resolves the authenticated user from the JWT subject (email), looks up their userId,
     * and asserts it matches the path userId. Throws AccessDeniedException on mismatch.
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

    /**
     * Fires when wikiReloadExecutor's bounded queue (1 running + 1 queued) is already full
     * and a third overlapping trigger is submitted (T-08-02, DoS mitigation).
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleTaskRejected(TaskRejectedException ex) {
        return ResponseEntity.status(503).body(Map.of(
                "message", "A wiki-reload is already in progress for this user; try again shortly."));
    }
}
