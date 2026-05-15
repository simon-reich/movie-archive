package de.moviearchive.auth;

import de.moviearchive.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldCreateUser_withPendingVerificationStatus() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldVerifyEmail_setsStatusActiveAndConsumesToken() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldLogin_returnsAccessTokenAndHttpOnlyCookie() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRotateRefreshToken_revokesOldAndIssuesNew() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldLogout_setsTokenRevoked() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldResetPassword_revokesAllRefreshTokens() {}
}
