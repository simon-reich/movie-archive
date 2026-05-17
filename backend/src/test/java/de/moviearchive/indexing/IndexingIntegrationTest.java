package de.moviearchive.indexing;

import de.moviearchive.AbstractOpenSearchTest;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Disabled("Wave 2 — plan 04-02")
class IndexingIntegrationTest extends AbstractOpenSearchTest {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        // TODO 04-02: delete movies, users, and the per-user OS index
    }

    @Test
    void shouldIndexFilm_afterPostgresPersist() {
        // TODO 04-02
    }

    @Test
    void shouldLeaveIndexedAtNull_whenOsFails() {
        // TODO 04-02
    }

    @Test
    void shouldCreateIndex_whenNotExists() {
        // TODO 04-02
    }

    @Test
    void shouldNotThrow_whenIndexAlreadyExists() {
        // TODO 04-02
    }

    @Test
    void shouldNormalizeAccents() {
        // TODO 04-02
    }

    @Test
    void shouldStemEnglishWords() {
        // TODO 04-02
    }
}
