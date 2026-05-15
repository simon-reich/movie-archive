package de.moviearchive.security;

import de.moviearchive.user.User;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-that-is-long-enough-32c";
    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirationMs", 900000L);

        testUser = new User("user@example.com", "hashedpassword");
        ReflectionTestUtils.setField(testUser, "id", UUID.randomUUID());
    }

    @Test
    void shouldGenerateAccessToken_withSubjectAndEmailClaim() {
        String token = jwtService.generateAccessToken(testUser);

        assertThat(token).isNotNull();
        // JWT has 3 dot-separated parts
        assertThat(token.split("\\.")).hasSize(3);
        // Extract subject (userId) from token
        String userId = jwtService.extractUserId(token);
        assertThat(userId).isEqualTo(testUser.getId().toString());
        // Extract email claim
        String email = jwtService.extractEmail(token);
        assertThat(email).isEqualTo("user@example.com");
    }

    @Test
    void shouldValidateToken_returnsClaimsForValidToken() {
        String token = jwtService.generateAccessToken(testUser);

        boolean valid = jwtService.validateToken(token);

        assertThat(valid).isTrue();
    }

    @Test
    void shouldRejectToken_whenSignatureInvalid() {
        String token = jwtService.generateAccessToken(testUser);
        // Tamper with signature by using a different key
        String wrongSecret = "wrong-secret-key-that-is-long-enough-32chars";
        JwtService wrongKeyService = new JwtService();
        ReflectionTestUtils.setField(wrongKeyService, "secret", wrongSecret);
        ReflectionTestUtils.setField(wrongKeyService, "accessTokenExpirationMs", 900000L);

        assertThatThrownBy(() -> wrongKeyService.validateToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectToken_whenTokenExpired() {
        // Create a JwtService with negative expiration so token is already expired
        JwtService expiredService = new JwtService();
        ReflectionTestUtils.setField(expiredService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(expiredService, "accessTokenExpirationMs", -1000L);

        String expiredToken = expiredService.generateAccessToken(testUser);

        assertThatThrownBy(() -> jwtService.validateToken(expiredToken))
                .isInstanceOf(JwtException.class);
    }
}
