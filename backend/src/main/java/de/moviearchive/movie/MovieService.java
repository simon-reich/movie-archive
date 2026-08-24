package de.moviearchive.movie;

import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.movie.dto.MovieInitiateResult;
import de.moviearchive.movie.dto.MovieStatusResponse;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import de.moviearchive.settings.SettingsService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class MovieService {

    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final TmdbClient tmdbClient;

    public MovieService(MovieRepository movieRepository,
                        UserRepository userRepository,
                        SettingsService settingsService,
                        TmdbClient tmdbClient) {
        this.movieRepository = movieRepository;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.tmdbClient = tmdbClient;
    }

    /**
     * Creates a Movie row with status=PENDING and returns a MovieInitiateResult.
     * Idempotent: if the same user already has this tmdbId, returns the existing UUID with isNew=false.
     *
     * Uses check-then-insert rather than catch-on-duplicate because JPA flushes at
     * transaction commit time — a DataIntegrityViolationException from a UNIQUE violation
     * would propagate past the catch block before the transaction ends.
     */
    public MovieInitiateResult initiate(String email, int tmdbId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        // Idempotency check: return existing UUID if this user already saved this film
        return movieRepository.findByUserIdAndTmdbId(user.getId(), tmdbId)
                .map(existing -> {
                    log.info("Duplicate save detected for userId={} tmdbId={} — returning existing UUID={}", user.getId(), tmdbId, existing.getId());
                    return new MovieInitiateResult(existing.getId(), false);
                })
                .orElseGet(() -> {
                    Movie movie = movieRepository.save(new Movie(user, tmdbId));
                    log.info("Movie initiated: movieId={} tmdbId={} userId={}", movie.getId(), tmdbId, user.getId());
                    return new MovieInitiateResult(movie.getId(), true);
                });
    }

    /**
     * Returns all TMDB IDs saved by the given user.
     * Used by GET /movies/saved-ids to populate the frontend duplicate-save badge.
     */
    @Transactional(readOnly = true)
    public List<Integer> getSavedTmdbIds(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return movieRepository.findTmdbIdsByUserId(user.getId());
    }

    /**
     * Returns the status of a movie, scoped to the authenticated user.
     * Throws AccessDeniedException if movieId does not belong to this user.
     */
    @Transactional(readOnly = true)
    public MovieStatusResponse getStatus(UUID movieId, UUID userId) {
        Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
                .orElseThrow(() -> new AccessDeniedException("Movie not found or access denied"));
        return new MovieStatusResponse(
                movie.getId().toString(),
                movie.getStatus().name(),
                movie.getTitle(),
                movie.getIndexedAt() != null ? movie.getIndexedAt().toString() : null
        );
    }

    /**
     * Convenience method for use from controllers that have email (from JWT subject)
     * but not userId. Resolves userId from email, then delegates to getStatus().
     */
    @Transactional(readOnly = true)
    public MovieStatusResponse getStatusByEmail(String email, UUID movieId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return getStatus(movieId, user.getId());
    }

    /**
     * Searches TMDB using the user's stored TMDB key.
     * Throws NoTmdbKeyException if no key is configured.
     */
    @Transactional(readOnly = true)
    public List<TmdbSearchResultItem> search(String email, String query) {
        String tmdbKey = resolveTmdbKey(email);
        return tmdbClient.search(query, tmdbKey);
    }

    /**
     * Resolves the user's stored TMDB key, decrypted. Extracted from search() so
     * BulkImportController can reuse the identical fail-fast 422 check synchronously,
     * before dispatching to the async bulk-import job.
     * Throws NoTmdbKeyException if no key is configured.
     */
    @Transactional(readOnly = true)
    public String resolveTmdbKey(String email) {
        var keys = settingsService.getApiKeys(email);
        String tmdbKey = (String) keys.get("tmdb");
        if (tmdbKey == null) {
            throw new NoTmdbKeyException("No TMDB key configured. Add your key in Settings.");
        }
        return tmdbKey;
    }
}
