package de.moviearchive.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the authenticated caller's own identity.
 * Security: id is ALWAYS derived from the JWT via auth.getName() (email) -> UserRepository
 * lookup. Never returns the raw User entity — that entity has no @JsonIgnore guard on its
 * passwordHash column, so only a minimal { id } map is ever serialized (T-09-04).
 * No SecurityConfig change needed: /users/** is not in the permitAll() list, so it falls
 * under the existing anyRequest().authenticated() catch-all (T-09-05).
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<Map<String, UUID>> me(Authentication auth) {
        String email = auth.getName();
        UUID id = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email))
                .getId();
        return ResponseEntity.ok(Map.of("id", id));
    }
}
