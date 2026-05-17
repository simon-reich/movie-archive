package de.moviearchive.search;

import de.moviearchive.search.dto.DashboardResponse;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * GET /dashboard — Returns archive stats, movie of the day, and recently-added films
 * for the authenticated user.
 * userId is ALWAYS derived from the JWT via resolveUserId — never from the request.
 */
@RestController
@RequestMapping("/dashboard")
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    public DashboardController(DashboardService dashboardService, UserRepository userRepository) {
        this.dashboardService = dashboardService;
        this.userRepository = userRepository;
    }

    /**
     * GET /dashboard
     * Aggregates stats from movies-{userId} and recently indexed films from Postgres.
     * Empty archive returns a valid empty-state body (totalFilms=0, empty lists,
     * movieOfTheDay=null) rather than 500 (05-RESEARCH.md Pitfall 7).
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard(Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        log.debug("Dashboard request for userId={}", userId);
        return ResponseEntity.ok(dashboardService.getDashboard("movies-" + userId, userId));
    }

    // ── Auth helper ────────────────────────────────────────────────────────────

    /**
     * Resolves userId from JWT email. auth.getName() returns email (not UUID).
     * Never use UUID.fromString(auth.getName()) — Pitfall 4.
     */
    private UUID resolveUserId(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
    }
}
