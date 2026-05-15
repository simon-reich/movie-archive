package de.moviearchive.auth;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectExpiredToken_whenVerificationTokenExpired() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectConsumedToken_whenVerificationTokenAlreadyUsed() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectExpiredResetToken_whenPasswordResetTokenExpired() {}
}
