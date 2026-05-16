package de.moviearchive.settings;

import de.moviearchive.auth.EmailAlreadyExistsException;
import de.moviearchive.auth.TokenAlreadyConsumedException;
import de.moviearchive.auth.TokenExpiredException;
import de.moviearchive.auth.TokenNotFoundException;
import de.moviearchive.settings.dto.ChangeEmailRequest;
import de.moviearchive.settings.dto.ChangePasswordRequest;
import de.moviearchive.settings.dto.SaveApiKeyRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/settings")
@Slf4j
public class SettingsController {

    private final SettingsService settingsService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PutMapping("/api-keys/{provider}")
    public ResponseEntity<Map<String, String>> saveApiKey(
            @PathVariable String provider,
            @Valid @RequestBody SaveApiKeyRequest req,
            Authentication authentication) {
        settingsService.saveApiKey(authentication.getName(), provider, req.key());
        return ResponseEntity.ok(Map.of("message", "API key saved."));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<Map<String, Object>> getApiKeys(Authentication authentication) {
        return ResponseEntity.ok(settingsService.getApiKeys(authentication.getName()));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            Authentication authentication) {
        settingsService.changePassword(authentication.getName(), req.currentPassword(), req.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/email")
    public ResponseEntity<Void> changeEmail(
            @Valid @RequestBody ChangeEmailRequest req,
            Authentication authentication) {
        settingsService.requestEmailChange(authentication.getName(), req.newEmail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/confirm-email")
    public ResponseEntity<Void> confirmEmail(@RequestParam String token) {
        try {
            settingsService.confirmEmailChange(token);
            return ResponseEntity.status(302)
                    .header("Location", appBaseUrl + "/settings?emailConfirmed=true")
                    .build();
        } catch (TokenAlreadyConsumedException e) {
            return ResponseEntity.status(302)
                    .header("Location", appBaseUrl + "/settings?emailError=token-used")
                    .build();
        } catch (TokenExpiredException e) {
            return ResponseEntity.status(302)
                    .header("Location", appBaseUrl + "/settings?emailError=token-expired")
                    .build();
        } catch (TokenNotFoundException e) {
            return ResponseEntity.status(302)
                    .header("Location", appBaseUrl + "/settings?emailError=invalid-token")
                    .build();
        }
    }

    // --- Exception handlers ---

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<Map<String, String>> handleInvalidApiKey(InvalidApiKeyException ex) {
        return ResponseEntity.status(422).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(400).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleEmailTaken(EmailAlreadyExistsException ex) {
        return ResponseEntity.status(409).body(Map.of("message", "This email address is no longer available."));
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("message", "Invalid provider. Use TMDB or OMDB."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Validation failed.");
        return ResponseEntity.status(400).body(Map.of("message", message));
    }
}
