package de.moviearchive.token;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldHandleGracePeriod_whenConcurrentRefreshWithinWindow() {}

    @Test
    @Disabled("Wave 0 stub — implement in Plan 02")
    void shouldRejectToken_whenGracePeriodExpired() {}
}
