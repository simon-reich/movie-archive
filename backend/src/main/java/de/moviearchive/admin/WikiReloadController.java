package de.moviearchive.admin;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * POST /admin/wiki-reload/{userId} triggers a fire-and-forget Wikipedia-only batch retry
 * (D-05) for the user's cooldown-eligible films missing wiki data. Returns 202 Accepted
 * immediately; the batch runs async on the dedicated wikiReloadExecutor. A third
 * overlapping trigger (beyond 1 running + 1 queued) is rejected with 503 (T-08-02).
 *
 * wikiReloadExecutor (core=1/max=1/queue=1) is a single global bean shared by ALL users,
 * not scoped per user (see AsyncConfig javadoc) — acceptable under this app's current
 * single-user-first scope (CLAUDE.md), but the 503 below deliberately describes a global,
 * not per-user, conflict so it stays accurate if a second user is ever active concurrently.
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
    private final WikiReloadProgressService progressService;

    public WikiReloadController(WikiReloadService wikiReloadService, UserRepository userRepository,
                                 WikiReloadProgressService progressService) {
        this.wikiReloadService = wikiReloadService;
        this.userRepository = userRepository;
        this.progressService = progressService;
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
     * GET /admin/wiki-reload/{userId}/progress — Server-Sent Events stream of live wiki-reload
     * progress (D-14-03). SseEmitter(Long.MAX_VALUE) never relies on the container's default
     * async timeout — a real reload can now run for many minutes at the new 30s pacing.
     * Returns 403 if the path userId does not belong to the authenticated JWT subject (T-14-01).
     */
    @GetMapping(value = "/{userId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable UUID userId, Authentication auth) {
        assertOwnership(auth, userId);
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        progressService.register(userId, emitter);
        return emitter;
    }

    /**
     * POST /admin/wiki-reload/{userId}/stop — requests a clean halt of the user's in-progress
     * batch-reload run (D-14-04/D-08). The run finishes its currently-processing movie before
     * exiting the loop — never a hard interrupt mid-fetch. Returns 403 if the path userId does
     * not belong to the authenticated JWT subject (T-14-01).
     */
    @PostMapping("/{userId}/stop")
    public ResponseEntity<Map<String, String>> stopReload(
            @PathVariable UUID userId, Authentication auth) {
        assertOwnership(auth, userId);
        progressService.requestStop(userId);
        log.info("Wiki batch-reload stop requested for userId={}", userId);
        return ResponseEntity.accepted().body(Map.of("status", "stop-requested"));
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
     * and a third overlapping trigger is submitted (T-08-02, DoS mitigation). The pool is a
     * single global bean shared by all users (see class javadoc), so the message
     * deliberately does not claim the conflict is specific to the requesting user.
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleTaskRejected(TaskRejectedException ex) {
        return ResponseEntity.status(503).body(Map.of(
                "message", "A wiki-reload batch is already in progress; try again shortly."));
    }
}
