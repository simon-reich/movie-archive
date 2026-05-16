package de.moviearchive.movie;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<Movie, UUID> {

    Optional<Movie> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndTmdbId(UUID userId, Integer tmdbId);

    /**
     * Eagerly fetches the user association so EnrichmentService can access
     * movie.getUser().getEmail() without a transaction or lazy-load.
     */
    @Query("SELECT m FROM Movie m JOIN FETCH m.user WHERE m.id = :id")
    Optional<Movie> findByIdWithUser(@Param("id") UUID id);
}
