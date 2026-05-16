package de.moviearchive.movie;

import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.movie.dto.MovieStatusResponse;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import de.moviearchive.settings.SettingsService;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
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
     * Creates a Movie row with status=PENDING and returns its UUID.
     * Idempotent: if the same user already has this tmdbId, returns the existing UUID.
     */
    public UUID initiate(String email, int tmdbId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));
        try {
            Movie movie = movieRepository.save(new Movie(user, tmdbId));
            log.info("Movie initiated: movieId={} tmdbId={} userId={}", movie.getId(), tmdbId, user.getId());
            return movie.getId();
        } catch (DataIntegrityViolationException ex) {
            // Same user already saved this film — return existing movie UUID (idempotent)
            log.info("Duplicate save detected for userId={} tmdbId={} — returning existing UUID", user.getId(), tmdbId);
            return movieRepository.findByUserIdAndTmdbId(user.getId(), tmdbId)
                    .map(Movie::getId)
                    .orElseThrow(() -> new RuntimeException("Unexpected: movie not found after duplicate key violation"));
        }
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
                movie.getTitle()
        );
    }

    /**
     * Convenience method for use from controllers that have email (from JWT subject)
     * but not userId. Resolves userId from email, then delegates to getStatus().
     */
    @Transactional(readOnly = true)
    public MovieStatusResponse getStatusByEmail(String email, UUID movieId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));
        return getStatus(movieId, user.getId());
    }

    /**
     * Searches TMDB using the user's stored TMDB key.
     * Throws NoTmdbKeyException if no key is configured.
     */
    @Transactional(readOnly = true)
    public List<TmdbSearchResultItem> search(String email, String query) {
        var keys = settingsService.getApiKeys(email);
        String tmdbKey = (String) keys.get("tmdb");
        if (tmdbKey == null) {
            throw new NoTmdbKeyException("No TMDB key configured. Add your key in Settings.");
        }
        return tmdbClient.search(query, tmdbKey);
    }
}
