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
     * Returns movies for the user with no resolved Wikipedia page (wiki_url IS NULL) whose
     * last attempt was either never made or is outside the cooldown window. Only
     * SUCCESS-status movies are eligible — ERROR-status movies never reached the Wikipedia
     * step and have no reliable title/year to look up, so including them would waste a
     * paced Wikipedia call on incomplete data. Used by batch-reload (ENRICH-02).
     *
     * <p>Superseded design decision (G-16-3, Phase 16 gap closure): an earlier version of
     * this query deliberately keyed eligibility on {@code wiki_plot IS NULL AND
     * wiki_critics IS NULL} instead of {@code wiki_url IS NULL}, to match the movie detail
     * page's own {@code v-if="movie.wikipediaPlot || movie.wikipediaCritics"} visibility
     * guard — the idea being that "has a page but no extractable content" should be treated
     * the same as "fully missing" and kept eligible for retry. In practice this caused a
     * movie whose real, correctly-matched Wikipedia page has no section under exactly the
     * names {@code WikipediaClient.findSectionIndex()} recognizes ("Plot" / "Critical
     * response" / "Reception" / "Critical reception") to be re-selected and re-fetched on
     * every single batch-reload run forever — re-fetching the same unchanged real article
     * deterministically reproduces the same (empty) plot/critics result every time.
     * Confirmed live: 41/305 movies in the dataset shared this exact shape. Meanwhile
     * {@code WikiReloadService.WikiRetryOutcome.SUCCESS} (the value the per-movie history
     * checkmark renders, D-09) already means only "a page was located," independent of
     * whether any content section was extracted — so such a movie displayed a SUCCESS
     * checkmark on every run while simultaneously never leaving the eligible pool, an
     * internally-inconsistent experience. Keying eligibility on {@code wiki_url IS NULL}
     * aligns this query with WikiRetryOutcome's existing "found" semantics: once a page is
     * located, it is never retried again. Accepted trade-off: such a movie's detail page
     * will continue showing "no Wikipedia data" (per that same v-if) without ever being
     * retried again — broadening WikipediaClient's section-name recognition (a distinct,
     * larger scope) is the follow-up that would let such a page's content actually populate
     * someday, and is intentionally NOT addressed by this change.
     */
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId "
           + "AND m.wikiUrl IS NULL "
           + "AND m.status = de.moviearchive.movie.MovieStatus.SUCCESS "
           + "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
    List<Movie> findEligibleForWikiReload(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
}
