package de.moviearchive.settings;

import de.moviearchive.auth.EmailAlreadyExistsException;
import de.moviearchive.auth.TokenAlreadyConsumedException;
import de.moviearchive.auth.TokenExpiredException;
import de.moviearchive.auth.TokenNotFoundException;
import de.moviearchive.auth.TokenUtils;
import de.moviearchive.mail.MailService;
import de.moviearchive.token.EmailChangeToken;
import de.moviearchive.token.EmailChangeTokenRepository;
import de.moviearchive.token.RefreshTokenRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class SettingsService {

    private final UserRepository userRepository;
    private final UserApiKeyRepository userApiKeyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EncryptionService encryptionService;
    private final TmdbKeyValidator tmdbKeyValidator;
    private final OmdbKeyValidator omdbKeyValidator;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final MailService mailService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    public SettingsService(UserRepository userRepository,
                           UserApiKeyRepository userApiKeyRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           BCryptPasswordEncoder passwordEncoder,
                           EncryptionService encryptionService,
                           TmdbKeyValidator tmdbKeyValidator,
                           OmdbKeyValidator omdbKeyValidator,
                           EmailChangeTokenRepository emailChangeTokenRepository,
                           MailService mailService) {
        this.userRepository = userRepository;
        this.userApiKeyRepository = userApiKeyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.encryptionService = encryptionService;
        this.tmdbKeyValidator = tmdbKeyValidator;
        this.omdbKeyValidator = omdbKeyValidator;
        this.emailChangeTokenRepository = emailChangeTokenRepository;
        this.mailService = mailService;
    }

    public void saveApiKey(String email, String providerStr, String rawKey) {
        ApiKeyProvider provider = ApiKeyProvider.valueOf(providerStr.toUpperCase());

        boolean valid = switch (provider) {
            case TMDB -> tmdbKeyValidator.validate(rawKey);
            case OMDB -> omdbKeyValidator.validate(rawKey);
        };

        if (!valid) {
            throw new InvalidApiKeyException("Invalid " + providerStr.toUpperCase() + " API key — check your key and try again.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));

        userApiKeyRepository.findByUserIdAndProvider(user.getId(), provider)
                .ifPresentOrElse(
                        existing -> {
                            existing.setEncryptedKey(encryptionService.encrypt(rawKey));
                            userApiKeyRepository.save(existing);
                        },
                        () -> userApiKeyRepository.save(new UserApiKey(user, provider, encryptionService.encrypt(rawKey)))
                );

        log.info("API key saved for provider={} userId={}", provider, user.getId());
    }

    public Map<String, Object> getApiKeys(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));

        String tmdbKey = userApiKeyRepository.findByUserIdAndProvider(user.getId(), ApiKeyProvider.TMDB)
                .map(k -> encryptionService.decrypt(k.getEncryptedKey()))
                .orElse(null);

        String omdbKey = userApiKeyRepository.findByUserIdAndProvider(user.getId(), ApiKeyProvider.OMDB)
                .map(k -> encryptionService.decrypt(k.getEncryptedKey()))
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("tmdb", tmdbKey);
        result.put("omdb", omdbKey);
        return result;
    }

    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    public void requestEmailChange(String email, String newEmail) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email));

        String rawToken = UUID.randomUUID().toString();
        String hash = TokenUtils.hashToken(rawToken);
        emailChangeTokenRepository.save(
                new EmailChangeToken(user, newEmail, hash, Instant.now().plus(24, ChronoUnit.HOURS)));

        mailService.sendEmailChangeConfirmation(newEmail, rawToken);
        // Do NOT check newEmail uniqueness here — enumeration protection (D-07/RESEARCH pitfall 4)
    }

    /**
     * Confirms an email change and returns the newly activated email address.
     * The controller uses the return value to update the session_email cookie in the redirect.
     */
    public String confirmEmailChange(String rawToken) {
        String hash = TokenUtils.hashToken(rawToken);
        EmailChangeToken token = emailChangeTokenRepository.findByTokenHash(hash)
                .orElseThrow(TokenNotFoundException::new);

        if (token.isExpired()) {
            throw new TokenExpiredException();
        }
        if (token.isConsumed()) {
            throw new TokenAlreadyConsumedException();
        }

        // Re-check uniqueness at confirm time (RESEARCH pitfall 4)
        if (userRepository.existsByEmail(token.getNewEmail())) {
            throw new EmailAlreadyExistsException("This email address is no longer available.");
        }

        token.setConsumedAt(Instant.now());
        emailChangeTokenRepository.save(token);

        User user = token.getUser();
        String oldEmail = user.getEmail();
        user.setEmail(token.getNewEmail());
        userRepository.save(user);

        // Notify old address AFTER confirming (RESEARCH open question 2)
        mailService.sendEmailChangeNotification(oldEmail);

        return token.getNewEmail();
    }
}
