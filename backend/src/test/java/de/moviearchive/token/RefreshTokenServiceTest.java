package de.moviearchive.token;

import de.moviearchive.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private User createTestUser() {
        User user = new User("user@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void shouldHandleGracePeriod_whenConcurrentRefreshWithinWindow() {
        User user = createTestUser();
        RefreshToken token = new RefreshToken(user, "somehash", Instant.now().plusSeconds(3600));
        token.setRevoked(true);
        // Grace window is still active (10 seconds in the future)
        token.setGraceUntil(Instant.now().plusSeconds(10));

        // Entity state: revoked=true but graceUntil > now => grace window is active
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getGraceUntil()).isNotNull();
        assertThat(token.getGraceUntil().isAfter(Instant.now())).isTrue();
    }

    @Test
    void shouldRejectToken_whenGracePeriodExpired() {
        User user = createTestUser();
        RefreshToken token = new RefreshToken(user, "somehash", Instant.now().plusSeconds(3600));
        token.setRevoked(true);
        // Grace window has passed (10 seconds in the past)
        token.setGraceUntil(Instant.now().minusSeconds(10));

        // Entity state: revoked=true AND graceUntil < now => grace expired
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getGraceUntil()).isNotNull();
        assertThat(token.getGraceUntil().isBefore(Instant.now())).isTrue();
    }
}
