package de.moviearchive.auth;

import de.moviearchive.mail.MailService;
import de.moviearchive.security.JwtService;
import de.moviearchive.token.EmailVerificationToken;
import de.moviearchive.token.EmailVerificationTokenRepository;
import de.moviearchive.token.PasswordResetToken;
import de.moviearchive.token.PasswordResetTokenRepository;
import de.moviearchive.token.RefreshToken;
import de.moviearchive.token.RefreshTokenRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
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

    @Test
    void shouldThrowEmailAlreadyExists_whenSignupWithDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        de.moviearchive.auth.dto.SignupRequest req =
                new de.moviearchive.auth.dto.SignupRequest("dup@example.com", "Password1!");

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void shouldThrowBadCredentials_whenLoginWithUnknownEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        de.moviearchive.auth.dto.LoginRequest req =
                new de.moviearchive.auth.dto.LoginRequest("unknown@example.com", "Password1!");

        assertThatThrownBy(() -> authService.login(req, null))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void shouldThrowAccountNotActive_whenLoginWithPendingAccount() {
        User user = new User("pending@example.com", "hashed");
        // status is PENDING_VERIFICATION by default

        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(user));

        de.moviearchive.auth.dto.LoginRequest req =
                new de.moviearchive.auth.dto.LoginRequest("pending@example.com", "Password1!");

        assertThatThrownBy(() -> authService.login(req, null))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void shouldReturnSilently_whenForgotPasswordWithUnknownEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // Must not throw — always returns normally for enumeration protection
        assertThatNoException().isThrownBy(() -> authService.forgotPassword("nobody@example.com"));
    }

    @Test
    void shouldReturnSilently_whenLogoutWithUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        // Must not throw when token not found
        assertThatNoException().isThrownBy(() -> authService.logout("unknown-raw-token"));
    }

    @Test
    void shouldThrowTokenExpired_whenRefreshWithExpiredToken() {
        User user = new User("user@example.com", "hashed");
        RefreshToken token = new RefreshToken(user, "somehash",
                Instant.now().minus(1, ChronoUnit.HOURS));  // expired

        when(refreshTokenRepository.findValidToken(anyString(), any(Instant.class)))
                .thenReturn(Optional.of(token));
        when(refreshTokenRepository.save(any())).thenReturn(token);

        assertThatThrownBy(() -> authService.refresh("raw-refresh-token", null))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void shouldThrowConsumedToken_whenResetPasswordTokenAlreadyUsed() {
        User user = new User("user@example.com", "hashed");
        PasswordResetToken token = new PasswordResetToken(
                user, "somehash",
                Instant.now().plus(1, ChronoUnit.HOURS));  // not yet expired
        token.setConsumedAt(Instant.now().minus(10, ChronoUnit.MINUTES));  // already consumed

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.resetPassword("raw-token", "newPass1!"))
                .isInstanceOf(TokenAlreadyConsumedException.class);
    }

    @Test
    void shouldReturnSilently_whenResendVerificationForActiveUser() {
        User user = new User("active@example.com", "hashed");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(user));

        // Must not throw and must not send email
        assertThatNoException().isThrownBy(
                () -> authService.resendVerification("active@example.com"));
    }
}
