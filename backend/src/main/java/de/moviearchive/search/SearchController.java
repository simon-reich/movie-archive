package de.moviearchive.search;

import de.moviearchive.search.dto.AutocompleteResponse;
import de.moviearchive.search.dto.FacetsResponse;
import de.moviearchive.search.dto.SearchRequest;
import de.moviearchive.search.dto.SearchResponse;
import de.moviearchive.user.UserRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * POST /search  — free-text + filtered + sorted paginated search
 * GET  /search/autocomplete — director / actors prefix suggestions
 *
 * Security: userId is ALWAYS derived from the JWT via resolveUserId(auth).
 * The request body and query params NEVER contain or influence the userId.
 * No UUID.fromString(auth.getName()) — auth.getName() returns email (Pitfall 4).
 */
@RestController
@RequestMapping("/search")
@Slf4j
public class SearchController {

    private final SearchService searchService;
    private final UserRepository userRepository;

    public SearchController(SearchService searchService, UserRepository userRepository) {
        this.searchService = searchService;
        this.userRepository = userRepository;
    }

    /**
     * POST /search
     * Executes a search against the caller's movies-{userId} index.
     * Supports free-text query, advanced filters, sort, and page-based pagination.
     */
    @PostMapping
    public ResponseEntity<SearchResponse> search(
            @RequestBody @Valid SearchRequest request,
            Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        String indexName = "movies-" + userId;
        log.debug("Search request for userId={} query='{}' sort={} page={}",
                userId, request.getQuery(), request.getSort(), request.getPage());
        return ResponseEntity.ok(searchService.search(indexName, request));
    }

    /**
     * GET /search/facets
     * Returns distinct values for chip-based filters (genres, contentRatings, languages, countries)
     * sourced from the caller's index — no hardcoded lists.
     */
    @GetMapping("/facets")
    public ResponseEntity<FacetsResponse> facets(Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        String indexName = "movies-" + userId;
        return ResponseEntity.ok(searchService.getFacets(indexName));
    }

    /**
     * GET /search/autocomplete?field=director&prefix=Nol
     * Returns up to 10 deduplicated name suggestions.
     * field must be "director" or "actors" — other values return 400 (T-05-02-04).
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<AutocompleteResponse> autocomplete(
            @RequestParam String field,
            @RequestParam String prefix,
            Authentication auth) throws IOException {
        UUID userId = resolveUserId(auth);
        String indexName = "movies-" + userId;
        return ResponseEntity.ok(searchService.autocomplete(indexName, field, prefix));
    }

    // ── Auth helper ────────────────────────────────────────────────────────────

    /**
     * Resolves the userId from the JWT subject (email).
     * CRITICAL: auth.getName() returns email, NOT UUID.
     * Never do UUID.fromString(auth.getName()) — that throws IllegalArgumentException.
     */
    private UUID resolveUserId(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
    }

    // ── Exception handlers ─────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Validation failed.");
        return ResponseEntity.status(400).body(Map.of("message", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("message", ex.getMessage()));
    }
}
