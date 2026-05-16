package de.moviearchive.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SettingsIntegrationTest extends AbstractWireMockTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        // Delete in dependency order: UserApiKey → User (cascade)
        // UserApiKey is cascade-deleted when User is deleted (ON DELETE CASCADE in FK)
        userRepository.deleteAll();
        greenMail.reset();
    }

    /** Creates an ACTIVE user with email test@example.com and password Password1! */
    private User createActiveUser() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User();
        user.setEmail("test@example.com");
        user.setPasswordHash(encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /**
     * POSTs /auth/login and returns "Bearer <token>" for use in Authorization header.
     * Requires createActiveUser() to have been called first.
     */
    private String loginAndGetToken(User user) throws Exception {
        String response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = new ObjectMapper().readTree(response).get("accessToken").asText();
        return "Bearer " + accessToken;
    }

    // -------------------------------------------------------------------------
    // SET-01: TMDB key
    // -------------------------------------------------------------------------

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldSaveTmdbKey_whenKeyIsValid() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldRejectInvalidTmdbKey_whenApiReturns401() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldReturnTmdbKeyPlaintext_onGet() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldUpdateExistingTmdbKey_withoutDuplicateConstraintViolation() {
    }

    // -------------------------------------------------------------------------
    // SET-02: OMDB key
    // -------------------------------------------------------------------------

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldSaveOmdbKey_whenKeyIsValid() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldRejectInvalidOmdbKey_whenApiReturnsFalseResponse() {
    }

    // -------------------------------------------------------------------------
    // SET-03: Password change
    // -------------------------------------------------------------------------

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldChangePassword_withCorrectCurrentPassword() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldRejectPasswordChange_whenCurrentPasswordIsWrong() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldRevokeAllRefreshTokens_onPasswordChange() {
    }

    // -------------------------------------------------------------------------
    // SET-04: Email change
    // -------------------------------------------------------------------------

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldSendEmailChangeConfirmation_toNewAddress() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldConfirmEmailChange_andUpdateUserEmail() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldRejectConflictingEmailChange_whenNewEmailAlreadyTaken() {
    }

    @Test
    @Disabled("stub — implement in plan 02-02")
    void shouldNotifyOldAddress_afterEmailChangeConfirmed() {
    }
}
