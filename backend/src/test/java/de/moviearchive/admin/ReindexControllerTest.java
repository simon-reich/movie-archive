package de.moviearchive.admin;

import de.moviearchive.AbstractOpenSearchTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@AutoConfigureMockMvc
@Disabled("Wave 3 — plan 04-03")
class ReindexControllerTest extends AbstractOpenSearchTest {

    @Test
    void shouldReturn403_whenUserMismatch() {
        // TODO 04-03
    }

    @Test
    void shouldFullReindex() {
        // TODO 04-03
    }

    @Test
    void shouldIndexOnlyPending() {
        // TODO 04-03
    }

    @Test
    void shouldReturnIndexedCount() {
        // TODO 04-03
    }
}
