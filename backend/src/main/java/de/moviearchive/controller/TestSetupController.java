package de.moviearchive.controller;

import de.moviearchive.movie.MovieRepository;
import de.moviearchive.settings.ApiKeyProvider;
import de.moviearchive.settings.EncryptionService;
import de.moviearchive.settings.UserApiKey;
import de.moviearchive.settings.UserApiKeyRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import de.moviearchive.user.UserStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("test")
@RequestMapping("/test")
@RequiredArgsConstructor
@Slf4j
public class TestSetupController {

    private final UserRepository userRepository;
    private final UserApiKeyRepository apiKeyRepository;
    private final MovieRepository movieRepository;
    private final EncryptionService encryptionService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${test.user.email:e2e@moviearchive.test}")
    private String testEmail;

    @Value("${test.user.password:E2ePassword1!}")
    private String testPassword;

    @Value("${test.tmdb.key:}")
    private String testTmdbKey;

    @PostMapping("/setup")
    @Transactional
    public ResponseEntity<Map<String, String>> setup() {
        // 1. Clean: delete existing test user and all their data
        userRepository.findByEmail(testEmail).ifPresent(u -> {
            movieRepository.deleteByUserId(u.getId());
            apiKeyRepository.deleteByUserId(u.getId());
            userRepository.delete(u);
        });
        // Flush the delete to DB before inserting the same email again —
        // without this JPA may batch INSERT before DELETE, hitting uq_users_email.
        userRepository.flush();

        // 2. Create a fully ACTIVE user (bypasses email verification flow)
        User user = new User(testEmail, passwordEncoder.encode(testPassword));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // 3. Seed encrypted TMDB key if provided
        if (testTmdbKey != null && !testTmdbKey.isBlank()) {
            UserApiKey key = new UserApiKey(user, ApiKeyProvider.TMDB, encryptionService.encrypt(testTmdbKey));
            apiKeyRepository.save(key);
        }

        log.info("Test user setup complete: {}", testEmail);
        return ResponseEntity.ok(Map.of("email", testEmail));
    }
}
