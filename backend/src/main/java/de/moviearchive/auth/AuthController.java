package de.moviearchive.auth;

import de.moviearchive.auth.dto.ForgotPasswordRequest;
import de.moviearchive.auth.dto.LoginRequest;
import de.moviearchive.auth.dto.LoginResponse;
import de.moviearchive.auth.dto.RefreshResponse;
import de.moviearchive.auth.dto.ResetPasswordRequest;
import de.moviearchive.auth.dto.SignupRequest;
import de.moviearchive.auth.dto.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    @Value("${cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${cookie.same-site:Lax}")
    private String cookieSameSite;

    public AuthController(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest req) {
        authService.signup(req);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        authService.verifyEmail(req.token());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.resendVerification(req.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        String ip = resolveClientIp(request);
        if (!rateLimitService.tryConsume(ip)) {
            long retryAfter = rateLimitService.estimateRetryAfterSeconds(ip);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("message", "Too many attempts. Try again in " + retryAfter + " seconds."));
        }

        LoginResponse resp = authService.login(req, response);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).build();
        }
        RefreshResponse resp = authService.refresh(refreshToken, response);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        // Clear the browser cookie regardless
        ResponseCookie clearCookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .maxAge(0)
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authService.buildClearSessionEmailCookie());
        response.addHeader(HttpHeaders.SET_COOKIE, authService.buildClearAccessTokenCookie());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req,
            HttpServletRequest request) {

        String ip = resolveClientIp(request);
        if (!rateLimitService.tryConsume(ip)) {
            long retryAfter = rateLimitService.estimateRetryAfterSeconds(ip);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .build();
        }

        authService.forgotPassword(req.email());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/validate-reset-token")
    public ResponseEntity<Void> validateResetToken(@org.springframework.web.bind.annotation.RequestParam String token) {
        authService.validateResetToken(token);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(Authentication authentication) {
        return ResponseEntity.ok(authService.me(authentication));
    }

    // --- Exception handlers ---

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleEmailExists(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(409).body(Map.of("message", "An account with this email already exists."));
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<Map<String, String>> handleAccountNotActive(AccountNotActiveException ex) {
        return ResponseEntity.status(403).body(Map.of("message", "Account not verified."));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(401).body(Map.of("message", "Invalid email or password."));
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, String>> handleTokenExpired(TokenExpiredException ex) {
        return ResponseEntity.status(400).body(Map.of("message", "Token expired."));
    }

    @ExceptionHandler(TokenAlreadyConsumedException.class)
    public ResponseEntity<Map<String, String>> handleTokenConsumed(TokenAlreadyConsumedException ex) {
        return ResponseEntity.status(400).body(Map.of("message", "Token already used."));
    }

    @ExceptionHandler(TokenNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTokenNotFound(TokenNotFoundException ex) {
        return ResponseEntity.status(400).body(Map.of("message", "Invalid token."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Validation failed.");
        return ResponseEntity.status(400).body(Map.of("message", message));
    }

    // --- Helpers ---

    private String resolveClientIp(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("X-Forwarded-For"))
                .map(s -> s.split(",")[0].trim())
                .orElse(request.getRemoteAddr());
    }
}
