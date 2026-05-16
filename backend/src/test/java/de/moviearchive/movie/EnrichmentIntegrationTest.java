package de.moviearchive.movie;

import de.moviearchive.AbstractWireMockTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class EnrichmentIntegrationTest extends AbstractWireMockTest {

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @Test
    @Disabled("SAVE-02: implement EnrichmentService + TmdbClient first")
    void shouldPersistTmdbData_afterEnrichment() {}

    @Test
    @Disabled("SAVE-04: implement WikipediaClient 6-step fallback first")
    void shouldSaveWithSuccess_whenWikipediaFails() {}

    @Test
    @Disabled("SAVE-05: implement status transitions first")
    void shouldTransitionToSuccess_afterEnrichment() {}
}
