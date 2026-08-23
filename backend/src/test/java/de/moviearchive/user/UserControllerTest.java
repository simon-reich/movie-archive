package de.moviearchive.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller test for GET /users/me — the first controller in the empty de.moviearchive.user
 * package. Needs only Postgres + JWT (no OpenSearch, no WireMock), so this extends the
 * lightweight AbstractIntegrationTest rather than AbstractOpenSearchTest.
 */
@AutoConfigureMockMvc
class UserControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        userRepository.deleteAll();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Creates an ACTIVE user with BCrypt-encoded password and returns the saved entity. */
    private User createActiveUser(String email) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        User user = new User(email, encoder.encode("Password1!"));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** Logs in and returns the "Bearer <token>" header value. */
    private String loginAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\",\"password\":\"Password1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(response).get("accessToken").asText();
        return "Bearer " + accessToken;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void me_returnsAuthenticatedUsersOwnId() throws Exception {
        User user = createActiveUser("me-own-id@example.com");
        String token = loginAndGetToken("me-own-id@example.com");

        mockMvc.perform(get("/users/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()));
    }

    @Test
    void me_returnsDifferentIdForDifferentUser() throws Exception {
        User userA = createActiveUser("me-user-a@example.com");
        User userB = createActiveUser("me-user-b@example.com");

        String tokenA = loginAndGetToken("me-user-a@example.com");
        String tokenB = loginAndGetToken("me-user-b@example.com");

        mockMvc.perform(get("/users/me").header("Authorization", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userA.getId().toString()));

        mockMvc.perform(get("/users/me").header("Authorization", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userB.getId().toString()));

        assertThat(userA.getId()).isNotEqualTo(userB.getId());
    }

    @Test
    void me_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(result -> assertThat(
                        result.getResponse().getStatus()).isIn(401, 403));
    }
}
