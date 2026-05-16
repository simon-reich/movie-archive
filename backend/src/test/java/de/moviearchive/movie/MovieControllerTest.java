package de.moviearchive.movie;

import de.moviearchive.AbstractWireMockTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MovieControllerTest extends AbstractWireMockTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("wikipedia.base-url", wireMock::baseUrl);
    }

    @Test
    @Disabled("SAVE-01: implement MovieController first")
    void shouldReturn202_whenSaveInitiated() {}

    @Test
    @Disabled("SAVE-01: implement GET /movies/search first")
    void shouldReturnSearchResults_whenTmdbKeyValid() {}

    @Test
    @Disabled("SAVE-01: implement GET /movies/search first")
    void shouldReturn422_whenNoTmdbKey() {}

    @Test
    @Disabled("SAVE-05: implement GET /movies/{id}/status first")
    void shouldReturnPendingStatus_immediately() {}

    @Test
    @Disabled("SAVE-05: implement GET /movies/{id}/status first")
    void shouldReturn403_whenAccessingOtherUsersStatus() {}
}
