package de.moviearchive.admin;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * TRACER (Plan 08-01) — POST /admin/wiki-reload/{userId} triggers a Wikipedia-only
 * batch retry for the user's films missing wiki data. Synchronous and unpaced in this
 * plan; Plan 08-02 switches batchReload() to fire-and-forget @Async and this endpoint's
 * response to 202 Accepted.
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
        return ResponseEntity.ok(Map.of("status", "completed"));
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
}
