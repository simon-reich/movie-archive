package de.moviearchive.movie;

import de.moviearchive.movie.dto.MovieDetailResponse;
import de.moviearchive.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for movie detail operations.
 * Security: userId is ALWAYS derived from the JWT via resolveUserId(auth).
 * Never trust a userId from the request body or path param alone.
 *
 * Note: MovieController already handles POST /movies/save, GET /movies/saved-ids,
 * and GET /movies/{id}/status. This controller adds GET /movies/{id} (full detail).
 * PATCH /movies/{id}/personal and DELETE /movies/{id} are added in plan 06-02.
 */
@RestController
@RequestMapping("/movies")
@RequiredArgsConstructor
@Slf4j
public class MovieDetailController {

    private final MovieDetailService movieDetailService;
    private final UserRepository userRepository;

    @GetMapping("/{id}")
    public ResponseEntity<MovieDetailResponse> getDetail(
            @PathVariable UUID id, Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(movieDetailService.getDetail(userId, id));
    }

    @PatchMapping("/{id}/personal")
    public ResponseEntity<Void> updatePersonal(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> fields,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        movieDetailService.updatePersonal(userId, id, fields);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMovie(
            @PathVariable UUID id, Authentication auth) {
        UUID userId = resolveUserId(auth);
        movieDetailService.deleteMovie(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Manual single-film Wikipedia retry (ENRICH-04). POST because this is a command (an
     * enrichment attempt with side effects), not an idempotent resource update — deliberately
     * distinct from the PATCH endpoint above. Runs synchronously on the request thread and
     * never checks the batch-reload cooldown (D-01); no coordination with the batch-reload
     * executor is added (D-02, overlap risk explicitly accepted).
     */
    @PostMapping("/{id}/retry-wiki")
    public ResponseEntity<MovieDetailResponse> retryWiki(
            @PathVariable UUID id, Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(movieDetailService.retryWiki(userId, id));
    }

    private UUID resolveUserId(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
    }
}
