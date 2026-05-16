package de.moviearchive.movie;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<Movie, UUID> {

    Optional<Movie> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndTmdbId(UUID userId, Integer tmdbId);

    Optional<Movie> findByUserIdAndTmdbId(UUID userId, Integer tmdbId);
}
