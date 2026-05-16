package de.moviearchive.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, UUID> {

    Optional<UserApiKey> findByUserIdAndProvider(UUID userId, ApiKeyProvider provider);
}
