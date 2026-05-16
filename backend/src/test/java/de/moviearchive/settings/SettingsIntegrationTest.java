package de.moviearchive.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.moviearchive.AbstractWireMockTest;
import de.moviearchive.auth.RateLimitService;
import de.moviearchive.auth.TokenUtils;
import de.moviearchive.token.EmailChangeToken;
import de.moviearchive.token.EmailChangeTokenRepository;
import de.moviearchive.token.RefreshToken;
import de.moviearchive.token.RefreshTokenRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SettingsIntegrationTest extends AbstractWireMockTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void overrideExternalBaseUrls(DynamicPropertyRegistry registry) {
        registry.add("tmdb.base-url", wireMock::baseUrl);
        registry.add("omdb.base-url", wireMock::baseUrl);
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserApiKeyRepository userApiKeyRepository;

    @Autowired
    private EmailChangeTokenRepository emailChangeTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void cleanDb() {
        userApiKeyRepository.deleteAll();
        emailChangeTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        greenMail.reset();
        rateLimitService.resetAll();
    }

    /** Creates an ACTIVE user with email test@example.com and password Password1! */
    private User createActiveUser() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User("test@example.com", encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /**
     * POSTs /auth/login and returns "Bearer <token>" for use in Authorization header.
     * Requires createActiveUser() to have been called first.
     */
    private String loginAndGetToken(User user) throws Exception {
        String response = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = new ObjectMapper().readTree(response).get("accessToken").asText();
        return "Bearer " + accessToken;
    }

    // -------------------------------------------------------------------------
    // WireMock stub helpers — use wireMock.stubFor() (extension instance, not static client)
    // -------------------------------------------------------------------------

    private void stubTmdbValidKey() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/3/configuration"))
                .withQueryParam("api_key", equalTo("valid-tmdb-key"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private void stubTmdbInvalidKey() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/3/configuration"))
                .withQueryParam("api_key", equalTo("invalid-tmdb-key"))
                .willReturn(aResponse().withStatus(401)));
    }

    private void stubOmdbValidKey() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/"))
                .withQueryParam("apikey", equalTo("valid-omdb-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Response\":\"True\",\"Title\":\"The Shawshank Redemption\"}")));
    }

    private void stubOmdbInvalidKey() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/"))
                .withQueryParam("apikey", equalTo("invalid-omdb-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Response\":\"False\",\"Error\":\"Invalid API key!\"}")));
    }

    // -------------------------------------------------------------------------
    // SET-01: TMDB key
    // -------------------------------------------------------------------------

    @Test
    void shouldSaveTmdbKey_whenKeyIsValid() throws Exception {
        stubTmdbValidKey();
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(put("/settings/api-keys/tmdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"valid-tmdb-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API key saved."));

        assertThat(userApiKeyRepository.findByUserIdAndProvider(user.getId(), ApiKeyProvider.TMDB)).isPresent();
    }

    @Test
    void shouldRejectInvalidTmdbKey_whenApiReturns401() throws Exception {
        stubTmdbInvalidKey();
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(put("/settings/api-keys/tmdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"invalid-tmdb-key\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Invalid TMDB API key — check your key and try again."));
    }

    @Test
    void shouldReturnTmdbKeyPlaintext_onGet() throws Exception {
        stubTmdbValidKey();
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        // Save key first
        mockMvc.perform(put("/settings/api-keys/tmdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"valid-tmdb-key\"}"))
                .andExpect(status().isOk());

        // Get keys — should return plaintext (D-03)
        mockMvc.perform(get("/settings/api-keys")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdb").value("valid-tmdb-key"))
                .andExpect(jsonPath("$.omdb").isEmpty());
    }

    @Test
    void shouldUpdateExistingTmdbKey_withoutDuplicateConstraintViolation() throws Exception {
        stubTmdbValidKey();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/3/configuration"))
                .withQueryParam("api_key", equalTo("valid-tmdb-key-2"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        User user = createActiveUser();
        String token = loginAndGetToken(user);

        // Save first time
        mockMvc.perform(put("/settings/api-keys/tmdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"valid-tmdb-key\"}"))
                .andExpect(status().isOk());

        // Save second time (upsert — should not throw duplicate constraint error)
        mockMvc.perform(put("/settings/api-keys/tmdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"valid-tmdb-key-2\"}"))
                .andExpect(status().isOk());

        // Only one record should exist for this user+provider combination
        List<UserApiKey> keys = userApiKeyRepository.findAll().stream()
                .filter(k -> k.getUser().getId().equals(user.getId()) && k.getProvider() == ApiKeyProvider.TMDB)
                .toList();
        assertThat(keys).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // SET-02: OMDB key
    // -------------------------------------------------------------------------

    @Test
    void shouldSaveOmdbKey_whenKeyIsValid() throws Exception {
        stubOmdbValidKey();
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(put("/settings/api-keys/omdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"valid-omdb-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API key saved."));

        assertThat(userApiKeyRepository.findByUserIdAndProvider(user.getId(), ApiKeyProvider.OMDB)).isPresent();
    }

    @Test
    void shouldRejectInvalidOmdbKey_whenApiReturnsFalseResponse() throws Exception {
        stubOmdbInvalidKey();
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(put("/settings/api-keys/omdb")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"invalid-omdb-key\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Invalid OMDB API key — check your key and try again."));
    }

    // -------------------------------------------------------------------------
    // SET-03: Password change
    // -------------------------------------------------------------------------

    @Test
    void shouldChangePassword_withCorrectCurrentPassword() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(post("/settings/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password1!\",\"newPassword\":\"NewPassword2@\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectPasswordChange_whenCurrentPasswordIsWrong() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(post("/settings/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPassword!\",\"newPassword\":\"NewPassword2@\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect."));
    }

    @Test
    void shouldRevokeAllRefreshTokens_onPasswordChange() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        // Create an additional refresh token to verify it gets revoked
        RefreshToken rt = new RefreshToken(user, TokenUtils.hashToken(UUID.randomUUID().toString()),
                Instant.now().plus(7, ChronoUnit.DAYS));
        refreshTokenRepository.save(rt);

        mockMvc.perform(post("/settings/password")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password1!\",\"newPassword\":\"NewPassword2@\"}"))
                .andExpect(status().isOk());

        List<RefreshToken> allTokens = refreshTokenRepository.findAll();
        assertThat(allTokens).isNotEmpty();
        assertThat(allTokens).allMatch(RefreshToken::isRevoked);
    }

    // -------------------------------------------------------------------------
    // SET-04: Email change
    // -------------------------------------------------------------------------

    @Test
    void shouldSendEmailChangeConfirmation_toNewAddress() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(post("/settings/email")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@new.example.com\"}"))
                .andExpect(status().isOk());

        MimeMessage[] messages = greenMail.getReceivedMessagesForDomain("new.example.com");
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).contains("Confirm");
    }

    @Test
    void shouldConfirmEmailChange_andUpdateUserEmail() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        // Request email change
        mockMvc.perform(post("/settings/email")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"changed@example.com\"}"))
                .andExpect(status().isOk());

        // Extract raw token from the confirmation email
        MimeMessage[] messages = greenMail.getReceivedMessagesForDomain("example.com");
        assertThat(messages).isNotEmpty();
        String rawToken = extractTokenFromEmail(messages[0]);

        // Confirm email change — Spring returns 302, MockMvc does not follow redirects
        mockMvc.perform(get("/settings/confirm-email")
                        .param("token", rawToken))
                .andExpect(status().isFound());

        assertThat(userRepository.findByEmail("changed@example.com")).isPresent();
    }

    @Test
    void shouldSetSessionEmailCookie_onSuccessfulEmailConfirm() throws Exception {
        // Bug B fix: after a successful email confirmation the redirect response must carry
        // a Set-Cookie: session_email=<new-email> so the frontend auth store updates immediately.
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        mockMvc.perform(post("/settings/email")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"cookie-check@example.com\"}"))
                .andExpect(status().isOk());

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        String rawToken = extractTokenFromEmail(messages[0]);

        mockMvc.perform(get("/settings/confirm-email")
                        .param("token", rawToken))
                .andExpect(status().isFound())
                .andExpect(result -> {
                    String setCookie = result.getResponse().getHeader("Set-Cookie");
                    assertThat(setCookie)
                            .as("session_email cookie must be set to new email on confirm redirect")
                            .contains("session_email=cookie-check@example.com");
                });
    }

    @Test
    void shouldRedirectToLoginWithError_whenConfirmedEmailAlreadyTaken() throws Exception {
        // Create the primary user
        User user = createActiveUser();
        // Create a second user who already has the target email
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User takenUser = new User("taken@example.com", encoder.encode("Password1!"));
        takenUser.setStatus(UserStatus.ACTIVE);
        userRepository.save(takenUser);

        // Insert an email-change token directly (we control the raw token value this way)
        String rawToken = UUID.randomUUID().toString();
        String hash = TokenUtils.hashToken(rawToken);
        EmailChangeToken directToken = new EmailChangeToken(user, "taken@example.com", hash,
                Instant.now().plus(24, ChronoUnit.HOURS));
        emailChangeTokenRepository.save(directToken);

        // Confirm — should redirect to /login?emailError=email-unavailable (Bug A2 fix:
        // returning JSON 409 from a browser-followed redirect link is bad UX)
        mockMvc.perform(get("/settings/confirm-email")
                        .param("token", rawToken))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Location"))
                        .contains("emailError=email-unavailable"));
    }

    @Test
    void shouldNotifyOldAddress_afterEmailChangeConfirmed() throws Exception {
        User user = createActiveUser();
        String token = loginAndGetToken(user);

        // Request email change
        mockMvc.perform(post("/settings/email")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"newaddr@example.com\"}"))
                .andExpect(status().isOk());

        // Exactly one email sent at request time (confirm link to new address)
        MimeMessage[] confirmMessages = greenMail.getReceivedMessages();
        assertThat(confirmMessages).hasSize(1);
        String rawToken = extractTokenFromEmail(confirmMessages[0]);

        // Confirm email change
        mockMvc.perform(get("/settings/confirm-email")
                        .param("token", rawToken))
                .andExpect(status().isFound());

        // After confirm: 2 emails total (confirm to new + notification to old)
        MimeMessage[] allMessages = greenMail.getReceivedMessages();
        assertThat(allMessages).hasSize(2);

        // Verify notification was sent to old address with expected subject
        boolean hasNotification = false;
        for (MimeMessage msg : allMessages) {
            Address[] recipients = msg.getAllRecipients();
            if (recipients != null) {
                for (Address addr : recipients) {
                    if (addr.toString().contains("test@example.com")
                            && msg.getSubject().contains("was changed")) {
                        hasNotification = true;
                    }
                }
            }
        }
        assertThat(hasNotification).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractTokenFromEmail(MimeMessage message) throws Exception {
        String content = message.getContent().toString();
        Pattern pattern = Pattern.compile("token=([\\w-]+)");
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find()).as("Email should contain a token= query param").isTrue();
        return matcher.group(1);
    }
}
