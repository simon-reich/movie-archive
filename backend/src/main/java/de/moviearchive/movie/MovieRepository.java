package de.moviearchive.movie;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<Movie, UUID> {

    Optional<Movie> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndTmdbId(UUID userId, Integer tmdbId);

    Optional<Movie> findByUserIdAndTmdbId(UUID userId, Integer tmdbId);

    /**
     * Eagerly fetches the user association so EnrichmentService can access
     * movie.getUser().getEmail() without a transaction or lazy-load.
     */
    @Query("SELECT m FROM Movie m JOIN FETCH m.user WHERE m.id = :id")
    Optional<Movie> findByIdWithUser(@Param("id") UUID id);

    /**
     * Returns all TMDB IDs saved by the given user.
     * Used by GET /movies/saved-ids to power the frontend duplicate-save guard.
     */
    @Query("SELECT m.tmdbId FROM Movie m WHERE m.user.id = :userId")
    List<Integer> findTmdbIdsByUserId(@Param("userId") UUID userId);

    /**
     * Returns all movies for the given user. Used by full reindex.
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId")
    List<Movie> findAllByUserId(@Param("userId") UUID userId);

    void deleteByUserId(UUID userId);

    /**
     * Returns movies not yet indexed in OpenSearch. Used by partial reindex.
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NULL")
    List<Movie> findByUserIdAndIndexedAtIsNull(@Param("userId") UUID userId);

    /**
     * Returns the N most recently indexed movies for the given user.
     * Used by DashboardService for the "recently added" dashboard section.
     * Pageable controls the limit (pass PageRequest.of(0, 10)).
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.indexedAt IS NOT NULL " +
           "ORDER BY m.indexedAt DESC")
    List<Movie> findRecentlyIndexedByUserId(@Param("userId") UUID userId, Pageable pageable);

    /**
     * TRACER version (Plan 08-01) — returns movies for the user missing Wikipedia data
     * (wiki_url IS NULL), with no cooldown filter. Used by the tracer batch-reload path.
     * Plan 08-02 replaces this with findEligibleForWikiReload, which adds cooldown-window
     * eligibility filtering (wiki_last_attempted_at IS NULL OR < cutoff).
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.wikiUrl IS NULL")
    List<Movie> findByUserIdAndWikiUrlIsNull(@Param("userId") UUID userId);
}
