package de.moviearchive.movie;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnrichmentServiceTest {

    @Test
    @Disabled("SAVE-03: implement EnrichmentService first")
    void shouldSkipOmdb_whenNoOmdbKey() {}

    @Test
    @Disabled("SAVE-03: implement EnrichmentService first")
    void shouldSkipOmdb_whenImdbIdNull() {}

    @Test
    @Disabled("SAVE-04: implement WikipediaClient fallback first")
    void shouldSaveWithSuccess_whenWikipediaFails() {}
}
