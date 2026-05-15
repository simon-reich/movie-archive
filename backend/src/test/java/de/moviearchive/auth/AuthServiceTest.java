package de.moviearchive.auth;

import de.moviearchive.mail.MailService;
import de.moviearchive.security.JwtService;
import de.moviearchive.token.EmailVerificationToken;
import de.moviearchive.token.EmailVerificationTokenRepository;
import de.moviearchive.token.PasswordResetToken;
import de.moviearchive.token.PasswordResetTokenRepository;
import de.moviearchive.token.RefreshTokenRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private MailService mailService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                emailVerificationTokenRepository,
                passwordResetTokenRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                mailService
        );
    }

    @Test
    void shouldRejectExpiredToken_whenVerificationTokenExpired() {
        User user = new User("test@example.com", "hashedPassword");
        EmailVerificationToken token = new EmailVerificationToken(
                user,
                "somehash",
                Instant.now().minus(1, ChronoUnit.HOURS)  // expired 1 hour ago
        );

        when(emailVerificationTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.verifyEmail("raw-token-value"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void shouldRejectConsumedToken_whenVerificationTokenAlreadyUsed() {
        User user = new User("test@example.com", "hashedPassword");
        EmailVerificationToken token = new EmailVerificationToken(
                user,
                "somehash",
                Instant.now().plus(1, ChronoUnit.HOURS)   // still valid
        );
        token.setConsumedAt(Instant.now().minus(1, ChronoUnit.HOURS)); // already consumed

        when(emailVerificationTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.verifyEmail("raw-token-value"))
                .isInstanceOf(TokenAlreadyConsumedException.class);
    }

    @Test
    void shouldRejectExpiredResetToken_whenPasswordResetTokenExpired() {
        User user = new User("test@example.com", "hashedPassword");
        PasswordResetToken token = new PasswordResetToken(
                user,
                "somehash",
                Instant.now().minus(1, ChronoUnit.HOURS)  // expired 1 hour ago
        );

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.resetPassword("raw-token-value", "newPassword123"))
                .isInstanceOf(TokenExpiredException.class);
    }
}
