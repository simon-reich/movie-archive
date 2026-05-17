package de.moviearchive.movie;

import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.movie.dto.MovieInitiateResult;
import de.moviearchive.movie.dto.MovieStatusResponse;
import de.moviearchive.movie.dto.SaveMovieRequest;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/movies")
@Slf4j
public class MovieController {

    private final MovieService movieService;
    private final EnrichmentService enrichmentService;

    public MovieController(MovieService movieService, EnrichmentService enrichmentService) {
        this.movieService = movieService;
        this.enrichmentService = enrichmentService;
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, String>> saveMovie(
            @Valid @RequestBody SaveMovieRequest req,
            Authentication auth) {
        MovieInitiateResult result = movieService.initiate(auth.getName(), req.tmdbId());
        if (result.isNew()) {
            enrichmentService.enrich(result.id());
        }
        return ResponseEntity.accepted().body(Map.of("id", result.id().toString()));
    }

    @GetMapping("/saved-ids")
    public ResponseEntity<Map<String, List<Integer>>> getSavedIds(Authentication auth) {
        List<Integer> ids = movieService.getSavedTmdbIds(auth.getName());
        return ResponseEntity.ok(Map.of("tmdbIds", ids));
    }

    @GetMapping("/search")
    public ResponseEntity<List<TmdbSearchResultItem>> search(
            @RequestParam String q,
            Authentication auth) {
        List<TmdbSearchResultItem> results = movieService.search(auth.getName(), q);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<MovieStatusResponse> getStatus(
            @PathVariable UUID id,
            Authentication auth) {
        MovieStatusResponse response = movieService.getStatusByEmail(auth.getName(), id);
        return ResponseEntity.ok(response);
    }

    // --- Exception handlers ---

    @ExceptionHandler(NoTmdbKeyException.class)
    public ResponseEntity<Map<String, String>> handleNoTmdbKey(NoTmdbKeyException ex) {
        return ResponseEntity.status(422).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("message", "Access denied."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Validation failed.");
        return ResponseEntity.status(400).body(Map.of("message", message));
    }
}
