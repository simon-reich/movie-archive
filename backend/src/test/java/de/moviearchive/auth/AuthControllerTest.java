package de.moviearchive.auth;

import de.moviearchive.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldReturn409_whenEmailAlreadyRegistered() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectUnverified_whenAccountPendingVerification() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectBadPassword_whenPasswordIncorrect() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldReturn200ForUnknownEmail_forgotPassword() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRateLimitLogin_afterTenRequests() {}
}
