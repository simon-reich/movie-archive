package de.moviearchive.auth;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.moviearchive.AbstractIntegrationTest;
import de.moviearchive.token.RefreshToken;
import de.moviearchive.token.RefreshTokenRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthIntegrationTest extends AbstractIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void cleanDb() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        greenMail.reset();
    }

    @Test
    void shouldCreateUser_withPendingVerificationStatus() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"newuser@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("newuser@example.com").orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void shouldVerifyEmail_setsStatusActiveAndConsumesToken() throws Exception {
        // Signup
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"verify@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isCreated());

        // Extract token from verification email
        String rawToken = extractTokenFromVerificationEmail();

        // Verify
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());

        User user = userRepository.findByEmail("verify@example.com").orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void shouldLogin_returnsAccessTokenAndHttpOnlyCookie() throws Exception {
        signupAndVerify("login@example.com", "Password1!");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        // Multiple Set-Cookie headers are returned; use getHeaders to get all of them.
        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        String refreshCookie = setCookies.stream()
                .filter(c -> c.startsWith("refresh_token="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));
        assertThat(refreshCookie).contains("HttpOnly");
        assertThat(refreshCookie).contains("Path=/");

        // access_token cookie must be present and NOT HttpOnly (readable by JS / SSR)
        String accessCookie = setCookies.stream()
                .filter(c -> c.startsWith("access_token="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("access_token cookie not found"));
        assertThat(accessCookie).doesNotContain("HttpOnly");
        assertThat(accessCookie).contains("Path=/");
    }

    @Test
    void shouldRotateRefreshToken_revokesOldAndIssuesNew() throws Exception {
        signupAndVerify("rotate@example.com", "Password1!");

        // Login to get refresh cookie
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"rotate@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String originalCookie = loginResult.getResponse().getHeader("Set-Cookie");
        String originalRefreshToken = extractCookieValue(originalCookie, "refresh_token");

        // Hash the original token to find it in DB
        String originalHash = TokenUtils.hashToken(originalRefreshToken);

        // Refresh
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", originalRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        // Old token should be revoked in DB (may still be in grace window, but revoked=true)
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
        assertThat(oldToken.isRevoked()).isTrue();

        // New cookie should be issued
        String newCookie = refreshResult.getResponse().getHeader("Set-Cookie");
        assertThat(newCookie).contains("refresh_token=");
        assertThat(newCookie).doesNotContain(originalRefreshToken);
    }

    @Test
    void shouldLogout_setsTokenRevoked() throws Exception {
        signupAndVerify("logout@example.com", "Password1!");

        // Login
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"logout@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        String refreshToken = extractCookieValue(setCookie, "refresh_token");

        // Logout
        mockMvc.perform(post("/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk());

        // Token should be revoked
        String hash = TokenUtils.hashToken(refreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void shouldResetPassword_revokesAllRefreshTokens() throws Exception {
        signupAndVerify("reset@example.com", "Password1!");

        // Login to create a refresh token
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reset@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        // Trigger forgot-password
        greenMail.reset();
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reset@example.com"}
                                """))
                .andExpect(status().isOk());

        // Extract reset token from email
        String rawResetToken = extractTokenFromResetEmail();

        // Reset password
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawResetToken + "\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk());

        // All refresh tokens for the user should be revoked
        User user = userRepository.findByEmail("reset@example.com").orElseThrow();
        List<RefreshToken> tokens = refreshTokenRepository.findAll().stream()
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(tokens).isNotEmpty();
        assertThat(tokens).allMatch(RefreshToken::isRevoked);
    }

    // --- Helpers ---

    private void signupAndVerify(String email, String password) throws Exception {
        greenMail.reset();
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());

        String rawToken = extractTokenFromVerificationEmail();
        mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());
        greenMail.reset();
    }

    private String extractTokenFromVerificationEmail() throws Exception {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        String body = (String) messages[messages.length - 1].getContent();
        return extractToken(body, "verify-email\\?token=([^\"&\\s<]+)");
    }

    private String extractTokenFromResetEmail() throws Exception {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        String body = (String) messages[messages.length - 1].getContent();
        return extractToken(body, "reset-password\\?token=([^\"&\\s<]+)");
    }

    private String extractToken(String body, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(body);
        assertThat(matcher.find()).as("Token not found in email body").isTrue();
        return matcher.group(1);
    }

    private String extractCookieValue(String setCookieHeader, String cookieName) {
        if (setCookieHeader == null) return null;
        Pattern pattern = Pattern.compile(cookieName + "=([^;]+)");
        Matcher matcher = pattern.matcher(setCookieHeader);
        return matcher.find() ? matcher.group(1) : null;
    }
}
