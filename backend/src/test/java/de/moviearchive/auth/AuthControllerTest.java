package de.moviearchive.auth;

import de.moviearchive.AbstractIntegrationTest;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDb() {
        userRepository.deleteAll();
    }

    @Test
    void shouldReturn409_whenEmailAlreadyRegistered() throws Exception {
        // Arrange — save a user directly to simulate existing account
        userRepository.save(new User("existing@example.com", passwordEncoder.encode("Password1!")));

        // Act + Assert
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"existing@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldRejectUnverified_whenAccountPendingVerification() throws Exception {
        // Arrange — user with PENDING_VERIFICATION status
        User user = new User("pending@example.com", passwordEncoder.encode("Password1!"));
        // status defaults to PENDING_VERIFICATION
        userRepository.save(user);

        // Act + Assert
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"pending@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldRejectBadPassword_whenPasswordIncorrect() throws Exception {
        // Arrange — active user with known password
        User user = new User("active@example.com", passwordEncoder.encode("CorrectPass1!"));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // Act + Assert
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"active@example.com","password":"WrongPass1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldReturn200ForUnknownEmail_forgotPassword() throws Exception {
        // Act + Assert — always 200 regardless of whether email exists
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@nowhere.com"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRateLimitLogin_afterTenRequests() throws Exception {
        // Use a unique IP suffix based on a random UUID to avoid bucket state from other tests
        String uniqueIp = "10.0." + (int)(Math.random() * 255) + "." + (int)(Math.random() * 255);

        // Send 10 requests — all should pass the rate limit check (they'll fail auth, but not 429)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", uniqueIp)
                            .content("""
                                    {"email":"ratelimit@example.com","password":"Password1!"}
                                    """))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }

        // 11th request should be rate limited
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", uniqueIp)
                        .content("""
                                {"email":"ratelimit@example.com","password":"Password1!"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Retry-After")).isNotNull());
    }
}
