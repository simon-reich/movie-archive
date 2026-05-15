package de.moviearchive.auth;

import de.moviearchive.auth.dto.LoginRequest;
import de.moviearchive.auth.dto.LoginResponse;
import de.moviearchive.auth.dto.RefreshResponse;
import de.moviearchive.auth.dto.SignupRequest;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    public AuthService(UserRepository userRepository,
                       EmailVerificationTokenRepository emailVerificationTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MailService mailService) {
        this.userRepository = userRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
    }

    public void signup(SignupRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new EmailAlreadyExistsException();
        }
        User user = new User(req.email(), passwordEncoder.encode(req.password()));
        userRepository.save(user);

        String rawToken = UUID.randomUUID().toString();
        String hash = TokenUtils.hashToken(rawToken);
        emailVerificationTokenRepository.save(
                new EmailVerificationToken(user, hash, Instant.now().plus(24, ChronoUnit.HOURS)));
        mailService.sendVerificationEmail(req.email(), rawToken);
    }

    public void verifyEmail(String rawToken) {
        String hash = TokenUtils.hashToken(rawToken);
        EmailVerificationToken token = emailVerificationTokenRepository.findByTokenHash(hash)
                .orElseThrow(TokenNotFoundException::new);
        if (token.isExpired()) {
            throw new TokenExpiredException();
        }
        if (token.isConsumed()) {
            throw new TokenAlreadyConsumedException();
        }
        token.setConsumedAt(Instant.now());
        emailVerificationTokenRepository.save(token);
        User user = token.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
    }

    public void resendVerification(String email) {
        // Always return silently — no enumeration
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() == UserStatus.ACTIVE) {
                return;
            }
            // Delete existing unused tokens for this user
            emailVerificationTokenRepository.findAll().stream()
                    .filter(t -> t.getUser().getId().equals(user.getId()) && !t.isConsumed())
                    .forEach(emailVerificationTokenRepository::delete);
            String rawToken = UUID.randomUUID().toString();
            String hash = TokenUtils.hashToken(rawToken);
            emailVerificationTokenRepository.save(
                    new EmailVerificationToken(user, hash, Instant.now().plus(24, ChronoUnit.HOURS)));
            mailService.sendVerificationEmail(email, rawToken);
        });
    }

    public LoginResponse login(LoginRequest req, HttpServletResponse response) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String rawRefresh = UUID.randomUUID().toString();
        String hash = TokenUtils.hashToken(rawRefresh);
        refreshTokenRepository.save(new RefreshToken(
                user, hash, Instant.now().plus(refreshTokenExpirationMs, ChronoUnit.MILLIS)));

        // Cookie path is /api/auth/refresh — the browser-visible URL (Caddy strips /api before forwarding)
        ResponseCookie cookie = ResponseCookie.from("refresh_token", rawRefresh)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ofMillis(refreshTokenExpirationMs))
                .path("/api/auth/refresh")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return new LoginResponse(accessToken, user.getEmail());
    }

    public RefreshResponse refresh(String rawRefreshToken, HttpServletResponse response) {
        String hash = TokenUtils.hashToken(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findValidToken(hash, Instant.now())
                .orElseThrow(TokenNotFoundException::new);
        if (token.isExpired()) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new TokenExpiredException();
        }

        // Rotate: mark old token revoked with 5-second grace window for concurrent requests
        token.setRevoked(true);
        token.setGraceUntil(Instant.now().plusSeconds(5));
        refreshTokenRepository.save(token);

        User user = token.getUser();
        String newAccessToken = jwtService.generateAccessToken(user);
        String newRawRefresh = UUID.randomUUID().toString();
        String newHash = TokenUtils.hashToken(newRawRefresh);
        refreshTokenRepository.save(new RefreshToken(
                user, newHash, Instant.now().plus(refreshTokenExpirationMs, ChronoUnit.MILLIS)));

        ResponseCookie newCookie = ResponseCookie.from("refresh_token", newRawRefresh)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ofMillis(refreshTokenExpirationMs))
                .path("/api/auth/refresh")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, newCookie.toString());

        return new RefreshResponse(newAccessToken, user.getEmail());
    }

    public void logout(String rawRefreshToken) {
        String hash = TokenUtils.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    public void forgotPassword(String email) {
        // ALWAYS return normally — enumeration protection (always 200)
        userRepository.findByEmail(email).ifPresentOrElse(user -> {
            if (user.getStatus() == UserStatus.ACTIVE) {
                String rawToken = UUID.randomUUID().toString();
                String hash = TokenUtils.hashToken(rawToken);
                passwordResetTokenRepository.save(
                        new PasswordResetToken(user, hash, Instant.now().plus(1, ChronoUnit.HOURS)));
                mailService.sendPasswordResetEmail(email, rawToken);
            }
        }, () -> log.debug("Forgot-password request for unknown email: {}", email));
    }

    public void resetPassword(String rawToken, String newPassword) {
        String hash = TokenUtils.hashToken(rawToken);
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hash)
                .orElseThrow(TokenNotFoundException::new);
        if (token.isExpired()) {
            throw new TokenExpiredException();
        }
        if (token.isConsumed()) {
            throw new TokenAlreadyConsumedException();
        }
        token.setConsumedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    public Map<String, String> me(Authentication authentication) {
        return Map.of("email", authentication.getName());
    }
}
