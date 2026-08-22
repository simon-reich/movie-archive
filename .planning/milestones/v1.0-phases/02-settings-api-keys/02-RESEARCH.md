# Phase 2: Settings & API Keys - Research

**Researched:** 2026-05-16
**Domain:** Spring Boot settings endpoints, AES-256-GCM encryption, email change flow, API key validation with WebClient, Nuxt 3 settings page
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Single `/settings` page with named anchor sections: **Account** (email change, password change), **API Keys** (TMDB key, OMDB key), **Import & Export** (placeholder only — no backend).
- **D-02:** Settings link appears in the existing **AppNav** component — a gear icon or "Settings" text link, visible when logged in. No dropdown menu.
- **D-03:** API keys are displayed in **plaintext** in the settings page — no masking. The backend decrypts the AES-256-GCM-encrypted key on read and returns the full plaintext value. User can directly copy-paste. Encryption at rest (AES-256-GCM) is unchanged.
- **D-04:** When saving a new API key, the backend **validates it against the real API** before storing: TMDB key → call TMDB `/3/configuration`, OMDB key → call OMDB with a test query. If the key is rejected by the API, return an error and do not store the key.
- **D-05:** After a successful **password change**: revoke all refresh tokens (including the current session), then immediately redirect the user to `/login` with an inline message: "Password changed. Please log in again."
- **D-06:** After successfully **saving an API key**: show an **inline success state** on the key input field (checkmark / "Saved" text). No toast, no redirect. Consistent with the inline error pattern from Phase 1.
- **D-07:** After submitting an **email change request**: stay on the settings page and show an inline "Check your inbox — click the link to confirm your new address" message below the email field. No redirect.
- **D-08:** Phase 2 adds a visually complete **"Import & Export" section** in the settings UI (buttons for Export CSV and Import CSV) but the buttons are either hidden behind a "coming soon" note or disabled. No backend endpoints for CSV in Phase 2.
- **D-09:** Backend error responses use `{"message": "..."}` flat JSON. Frontend reads `.message` directly.
- **D-10:** Errors shown as **inline form errors** (not toasts). Success feedback is also inline (D-06, D-07).

### Claude's Discretion
- Exact visual layout and spacing of the settings sections.
- Whether the "Import & Export" placeholder shows a "coming soon" badge or simply disabled buttons.
- Exact wording of validation error messages (e.g., "Invalid TMDB API key — check your key and try again").
- Flyway migration version number for `user_api_keys` table (V5 or next available).

### Deferred Ideas (OUT OF SCOPE)
- **SET-05 CSV Export** — Deferred until after Phase 3. Phase 2 adds UI placeholder only.
- **SET-06 CSV Import** — Same reason. Deferred until after Phase 3.
- **Letterboxd CSV import** — Explicitly v2 (FEAT-V2-01). Not in scope for v1 at all.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SET-01 | User can save and update TMDB API key (AES-256-GCM encrypted, displayed plaintext per D-03) | AES-256-GCM pattern from CLAUDE.md; V5 Flyway migration; `user_api_keys` table schema from data-model.md; PUT /settings/api-keys/tmdb from auth-flows.md |
| SET-02 | User can optionally save and update OMDB API key (same encryption) | Same pattern as SET-01; OMDB optional flag in service logic |
| SET-03 | User can change password (current password required; all sessions invalidated) | Pattern from `AuthService.resetPassword()` — `revokeAllByUserId` already exists; needs `SettingsService.changePassword()` with BCrypt verify |
| SET-04 | User can change email (token link to new address; old address notified) | `EmailChangeToken` entity + repository already exist in codebase; `email-changed.html` Thymeleaf template needed; confirm-email endpoint needed |
| SET-05 | CSV export placeholder UI only | Disabled button in Import & Export section; zero backend work |
| SET-06 | CSV import placeholder UI only | Same as SET-05 |
</phase_requirements>

---

## Summary

Phase 2 is a settings feature built entirely on patterns already established in Phase 1. The backend needs one new package (`settings/`) with a controller, service, and entity (`UserApiKey`). The encryption infrastructure is already wired (`encryption.master-key` in `application.properties`), just not yet used. Token patterns (single-use, SHA-256 hash, `consumed_at`) are fully established and directly reused for the email-change flow. `RefreshTokenRepository.revokeAllByUserId()` exists and is called in the password reset flow, making the password-change session-revocation trivial to add.

The frontend is a single new page (`/settings`) using all existing Phase 1 components (`AuthCard`, `FormField`, `InputText`, `ButtonPrimary`, `FormErrorBanner`). A companion `useSettings` composable mirrors the pattern of `useAuth`. The only structurally new element is the inline success state for API key saves (D-06), which Phase 1 had no occasion to build.

The only moderately complex piece is the API key validation call (D-04): the backend must call TMDB `/3/configuration` or OMDB `/?i=tt0111161` synchronously during the PUT request (not async) and gate the DB write on a valid response. WireMock stubs must cover both valid and invalid key scenarios.

**Primary recommendation:** Build backend first (migration → entity → service → controller), then frontend page, then tests wave-by-wave. EmailChangeToken entity and repository already exist — wire the confirm-email endpoint as a separate task in the same plan.

---

## Project Constraints (from CLAUDE.md)

| Directive | Applies To |
|-----------|------------|
| AES-256-GCM: raw JDK `Cipher`, 12-byte IV prepended to ciphertext, master key from `${ENCRYPTION_MASTER_KEY}` ENV | SET-01, SET-02 |
| Never reuse IV — generate fresh per encrypt call | SET-01, SET-02 |
| WebClient (not RestTemplate) for external API calls | D-04 key validation |
| WireMock stubs for all external APIs in tests | D-04 TMDB and OMDB validation stubs |
| Testcontainers real PostgreSQL (no H2) | All integration tests |
| Error responses: `{"message": "..."}` flat JSON | All settings endpoints |
| `@Async` / `@Retryable` applies to Phase 3+ pipeline — not needed for synchronous key validation in Phase 2 | D-04 |
| Tests ship with the feature — no merge without tests | Every plan |
| Commits: `MOV-XX: <summary>` format | Workflow |
| Flyway: next migration is V5 | `user_api_keys` table |
| Java 25 toolchain, Spring Boot 3.5.0 | All backend code |
| Lombok `@Getter/@Setter/@NoArgsConstructor` on entities, `@Slf4j` for logging | `UserApiKey` entity |
| UUID primary keys, Spring Data JPA repositories | `UserApiKey`, `UserApiKeyRepository` |

---

## Standard Stack

### Already In Place (no new deps needed)

| Component | Version | Source | Purpose |
|-----------|---------|--------|---------|
| Spring Boot Web | 3.5.0 (BOM) | `build.gradle.kts` | REST endpoints |
| Spring Security | BOM-managed | `build.gradle.kts` | `anyRequest().authenticated()` covers `/settings/**` already |
| Spring Data JPA | BOM-managed | `build.gradle.kts` | `UserApiKeyRepository` |
| Flyway | BOM-managed | `build.gradle.kts` | V5 migration |
| WebFlux / WebClient | BOM-managed | `build.gradle.kts` (webflux starter present) | Synchronous API key validation via `.block()` |
| Lombok | BOM-managed | `build.gradle.kts` | Entity boilerplate |
| JDK `javax.crypto` | JDK 25 built-in | — | AES-256-GCM encrypt/decrypt |
| GreenMail 2.1.3 (test) | 2.1.3 | `build.gradle.kts` | Email assertion in integration tests |
| WireMock 3.13.0 (test) | 3.13.0 | `build.gradle.kts` | TMDB/OMDB stub responses |
| Testcontainers (test) | BOM-managed | `build.gradle.kts` | Real Postgres |
| Vitest + MSW (FE test) | see package.json | frontend | `useSettings` composable tests |

**No new dependencies required for Phase 2.** [VERIFIED: build.gradle.kts]

---

## Architecture Patterns

### Recommended Backend Package Structure

```
backend/src/main/java/de/moviearchive/
├── settings/
│   ├── SettingsController.java          # PUT /settings/api-keys/{provider}, POST /settings/password, POST /settings/email, GET /settings/confirm-email
│   ├── SettingsService.java             # Business logic — encrypt, validate, persist
│   ├── UserApiKey.java                  # JPA entity
│   ├── UserApiKeyRepository.java        # findByUserIdAndProvider()
│   ├── ApiKeyProvider.java              # enum TMDB / OMDB
│   ├── EncryptionService.java           # AES-256-GCM encrypt/decrypt
│   ├── TmdbKeyValidator.java            # WebClient call to /3/configuration
│   ├── OmdbKeyValidator.java            # WebClient call to /?i=tt0111161
│   └── dto/
│       ├── SaveApiKeyRequest.java       # { "key": "..." }
│       ├── ChangePasswordRequest.java   # { "currentPassword": "...", "newPassword": "..." }
│       └── ChangeEmailRequest.java      # { "newEmail": "..." }
```

### Pattern 1: AES-256-GCM EncryptionService

**What:** Stateless service bean wrapping JDK Cipher. Encrypt returns `byte[]` (12-byte IV + ciphertext). Stored as Base64 TEXT in Postgres. Decrypt splits the stored bytes back into IV + ciphertext.

**Key rule:** Never reuse IV — `SecureRandom.generateSeed(12)` per encrypt call. [VERIFIED: CLAUDE.md]

```java
// Source: CLAUDE.md §AES-256-GCM Encryption
@Service
public class EncryptionService {

    private final SecretKey masterKey;

    public EncryptionService(@Value("${encryption.master-key}") String masterKeyStr) {
        byte[] keyBytes = masterKeyStr.getBytes(StandardCharsets.UTF_8);
        // Ensure exactly 32 bytes for AES-256
        keyBytes = Arrays.copyOf(keyBytes, 32);
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Combined) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Combined);
            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
```

### Pattern 2: UserApiKey Entity

```java
// Source: data-model.md + established entity patterns from Phase 1
@Entity
@Table(name = "user_api_keys")
@Getter @Setter @NoArgsConstructor
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApiKeyProvider provider;   // TMDB or OMDB

    @Column(name = "encrypted_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedKey;

    public UserApiKey(User user, ApiKeyProvider provider, String encryptedKey) {
        this.user = user;
        this.provider = provider;
        this.encryptedKey = encryptedKey;
    }
}
```

### Pattern 3: API Key Validation with WebClient (synchronous, blocking)

Key validation in Phase 2 is **synchronous** — the PUT request waits for the API response before storing. No `@Async`. `.block()` is acceptable here because it runs in a Spring MVC thread (not a reactive event loop). [VERIFIED: CLAUDE.md §WebClient vs RestTemplate]

```java
// Source: api-contracts.md + CLAUDE.md §WebClient
@Component
public class TmdbKeyValidator {

    private final WebClient webClient;

    public TmdbKeyValidator(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("https://api.themoviedb.org").build();
    }

    /** Returns true if key is valid, false if 401 is returned. Throws on network error. */
    public boolean validate(String apiKey) {
        try {
            webClient.get()
                .uri("/3/configuration?api_key={key}", apiKey)
                .retrieve()
                .toBodilessEntity()
                .block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                return false;
            }
            throw e;  // network error / server error — rethrow; controller returns 502
        }
    }
}
```

OMDB validation call: `GET https://www.omdbapi.com/?apikey={key}&i=tt0111161` — a known valid IMDB ID (The Shawshank Redemption). A 200 with `{"Response":"False","Error":"Invalid API key!"}` means invalid key; a 200 with `Response: "True"` means valid. [VERIFIED: api-contracts.md]

### Pattern 4: Password Change — reuse existing revokeAll

`RefreshTokenRepository.revokeAllByUserId(UUID userId)` already exists (called by `AuthService.resetPassword()`). `SettingsService.changePassword()` replicates this pattern exactly:

1. Load user by `authentication.getName()` (email from JWT)
2. BCrypt verify `currentPassword` against `user.getPasswordHash()`
3. Encode and set `newPassword`
4. Call `refreshTokenRepository.revokeAllByUserId(user.getId())`
5. Return 200 — frontend clears auth store and navigates to `/login` with message

### Pattern 5: Email Change Flow

Two-endpoint flow:
1. `POST /settings/email` — create `EmailChangeToken` (entity + repo already exist), send mail to new address, send notification to old address, return 200
2. `GET /settings/confirm-email?token=...` — consume token, update `user.email`, return 200

The `EmailChangeToken` entity at `de.moviearchive.token.EmailChangeToken` already exists with the correct schema (`user_id`, `new_email`, `token_hash`, `expires_at`, `consumed_at`). [VERIFIED: codebase]

Mail: `email-changed.html` Thymeleaf template needed in `backend/src/main/resources/templates/mail/`. Two sends:
- To `newEmail`: "Click to confirm your new email address" + confirm link
- To `oldEmail` (user's current email): "Your email address was recently changed" notification (no link needed)

### Pattern 6: Frontend Settings Page Structure

Single `/settings` route. No full-screen auth-card layout — use a page layout with sections. Reuse `FormField`, `InputText`, `ButtonPrimary`, `FormErrorBanner`. Add a `useSettings` composable alongside `useAuth`.

```
frontend/
├── pages/
│   └── settings.vue               # Single page, three named anchor sections
├── composables/
│   └── useSettings.ts             # saveApiKey, changePassword, changeEmail
└── test/
    └── unit/
        ├── pages/settings.spec.ts
        └── composables/useSettings.spec.ts
```

`useSettings` composable pattern mirrors `useAuth`:

```typescript
// Source: established pattern from useAuth.ts
export function useSettings() {
  async function saveApiKey(provider: 'tmdb' | 'omdb', key: string): Promise<void> {
    await $fetch(`/api/settings/api-keys/${provider}`, {
      method: 'PUT',
      body: { key },
      credentials: 'include',
    })
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await $fetch('/api/settings/password', {
      method: 'POST',
      body: { currentPassword, newPassword },
      credentials: 'include',
    })
  }

  async function changeEmail(newEmail: string): Promise<void> {
    await $fetch('/api/settings/email', {
      method: 'POST',
      body: { newEmail },
      credentials: 'include',
    })
  }

  async function loadApiKeys(): Promise<{ tmdb: string | null; omdb: string | null }> {
    return await $fetch('/api/settings/api-keys', {
      credentials: 'include',
    })
  }

  return { saveApiKey, changePassword, changeEmail, loadApiKeys }
}
```

### Anti-Patterns to Avoid

- **Masking API keys on read:** D-03 explicitly requires plaintext return. Do not mask server-side.
- **Async key validation:** D-04 validation is synchronous — block the request until the API responds. Do not fire-and-forget.
- **Reusing GCM IV:** Always `new SecureRandom().nextBytes(iv)` per encrypt call. Same IV = broken encryption.
- **Self-invoking @Async/@Retryable:** Not applicable in Phase 2 (no async work), but do not retrofit these onto the synchronous key-validation call.
- **Email uniqueness check bypass:** When confirming email change, verify the new email is not already in use by another user before updating.
- **Sending email-change notification to old address before token confirmed:** Only send the notification after the confirm-email endpoint successfully consumes the token and updates the DB.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| AES-256-GCM encryption | Custom cipher wrapper | Raw JDK `Cipher.getInstance("AES/GCM/NoPadding")` as per CLAUDE.md | Spring Security `AesBytesEncryptor` forces PBKDF2 — unnecessary overhead when master key is already 32 random bytes |
| HTTP calls for key validation | `RestTemplate` | `WebClient` (already on classpath via webflux starter) | RestTemplate is maintenance mode; CLAUDE.md mandates WebClient |
| Token hashing | Custom hash | `TokenUtils.hashToken()` (already in codebase at `de.moviearchive.auth.TokenUtils`) | Established pattern, already tested |
| Session revocation on password change | Custom query | `refreshTokenRepository.revokeAllByUserId()` (already exists) | Used by `AuthService.resetPassword()` — identical semantics |
| EmailChangeToken entity | New class from scratch | Extend the existing `EmailChangeToken` at `de.moviearchive.token` | Entity + repository + migration already exist |
| Frontend error handling pattern | Toast library | Inline `FormErrorBanner` (already built) | Phase 1 established inline errors as the project pattern |

---

## Common Pitfalls

### Pitfall 1: WebClient `.block()` in reactive context
**What goes wrong:** If `WebClient.block()` is called from within a reactive event loop thread, it deadlocks.
**Why it happens:** Spring WebFlux uses non-blocking threads; blocking on one is forbidden.
**How to avoid:** In Phase 2, key validation runs in a Spring MVC (Tomcat) thread — `.block()` is safe there. Do not run it inside a reactive chain or `@Async` method.
**Warning signs:** `IllegalStateException: block()/blockFirst()/blockLast() are blocking` in test output.

### Pitfall 2: GCM Authentication Tag Failure on Decrypt
**What goes wrong:** `AEADBadTagException` when decrypting a stored key.
**Why it happens:** IV was reused, master key changed between encrypt/decrypt, or stored value was corrupted.
**How to avoid:** Always generate fresh IV per encrypt. In tests, use the same `EncryptionService` bean instance. Ensure `encryption.master-key` is stable (same value in `application-test.properties`).
**Warning signs:** Decryption fails for records that were just written — points to IV mismatch or key mismatch.

### Pitfall 3: UPSERT vs INSERT for user_api_keys
**What goes wrong:** Saving a key twice throws `DataIntegrityViolationException` on the `UNIQUE(user_id, provider)` constraint.
**Why it happens:** `userApiKeyRepository.save()` with a new entity creates a duplicate rather than updating.
**How to avoid:** `SettingsService.saveApiKey()` must call `userApiKeyRepository.findByUserIdAndProvider()` first — update the existing record if found, insert if not.
**Warning signs:** `PSQLException: duplicate key value violates unique constraint` in integration test logs.

### Pitfall 4: Email Change Token — new email already taken
**What goes wrong:** User requests email change to `taken@example.com`, then someone else registers with that email before the token is confirmed. Consuming the token would overwrite the other user's email.
**Why it happens:** Email uniqueness is only checked at request time, not at confirmation time.
**How to avoid:** At `GET /settings/confirm-email`: re-check `userRepository.existsByEmail(token.getNewEmail())` before updating. If taken, return 409 with `{"message": "This email address is no longer available."}`.
**Warning signs:** `DataIntegrityViolationException` on the `users.email` unique constraint during confirm — means the check was missing.

### Pitfall 5: Password change — frontend must clear auth store before redirect
**What goes wrong:** Frontend calls `POST /settings/password` → 200 → navigates to `/login`. But auth store still has the old access token, so auth middleware considers user still logged in and immediately redirects back to `/settings`.
**Why it happens:** Access token (15 min lifetime) is still valid even though all refresh tokens are revoked. The middleware checks `isAuthenticated` (store state), not token validity.
**How to avoid:** After successful password change response, call `authStore.clearAuth()` **before** `navigateTo('/login')`. The middleware's public-route check then allows the navigation.
**Warning signs:** Infinite redirect loop between `/settings` and `/login` after password change.

### Pitfall 6: WireMock dynamic port not injected into WebClient base URL
**What goes wrong:** Key validation integration test calls the real TMDB/OMDB API instead of WireMock, or fails to connect.
**Why it happens:** `TmdbKeyValidator` has the base URL hardcoded as a constructor argument; WireMock starts on a dynamic port.
**How to avoid:** Inject `tmdb.base-url` and `omdb.base-url` as `@Value` properties. Override them in `@DynamicPropertySource` of the test class to `wireMock.baseUrl()`. The test class should extend `AbstractWireMockTest`.
**Warning signs:** Test hangs or `ConnectException` — WireMock was not wired up.

### Pitfall 7: Confirm-email endpoint lands on a Nuxt page, not a backend endpoint
**What goes wrong:** User clicks the confirm-email link (`/settings/confirm-email?token=...`). Nuxt intercepts the route because it matches a page pattern, not `/api/*`.
**Why it happens:** Caddy routes `/api/*` to Spring; anything else goes to Nuxt. The confirm-email link needs to hit Spring.
**How to avoid:** The confirm-email link in the email template must use `/api/settings/confirm-email?token=...` so Caddy routes it to Spring. The Spring controller returns a redirect to `/settings?emailConfirmed=true`, which Nuxt handles as a page with inline success message.
**Warning signs:** 404 or Nuxt page-not-found when clicking the confirm link.

---

## Code Examples

### V5 Flyway Migration

```sql
-- Source: data-model.md + V3__create_token_tables.sql pattern
CREATE TABLE user_api_keys (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    provider      VARCHAR(10)  NOT NULL CHECK (provider IN ('TMDB', 'OMDB')),
    encrypted_key TEXT         NOT NULL,

    CONSTRAINT pk_user_api_keys PRIMARY KEY (id),
    CONSTRAINT fk_uak_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_uak_user_provider UNIQUE (user_id, provider)
);
```

### SecurityConfig — no changes needed

`anyRequest().authenticated()` already covers `/settings/**`. The `JwtAuthFilter` already extracts `Authentication` from the JWT for all authenticated endpoints. [VERIFIED: SecurityConfig.java]

### SettingsController skeleton

```java
// Source: AuthController.java pattern
@RestController
@RequestMapping("/settings")
@Slf4j
public class SettingsController {

    private final SettingsService settingsService;

    @PutMapping("/api-keys/{provider}")
    public ResponseEntity<Map<String, String>> saveApiKey(
            @PathVariable String provider,
            @Valid @RequestBody SaveApiKeyRequest req,
            Authentication authentication) {
        settingsService.saveApiKey(authentication.getName(), provider, req.key());
        return ResponseEntity.ok(Map.of("message", "API key saved."));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<Map<String, String>> getApiKeys(Authentication authentication) {
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
        settingsService.confirmEmailChange(token);
        // Redirect to settings page with success flag
        return ResponseEntity.status(302)
                .header("Location", appBaseUrl + "/settings?emailConfirmed=true")
                .build();
    }

    // Exception handlers follow AuthController pattern: Map.of("message", "...") + appropriate status
}
```

### MSW Handler for Settings (frontend tests)

```typescript
// Source: frontend/test/mocks/handlers/auth.ts pattern
export const settingsHandlers = [
  http.get('/api/settings/api-keys', () => {
    return HttpResponse.json({ tmdb: 'test-tmdb-key', omdb: null })
  }),
  http.put('/api/settings/api-keys/:provider', async ({ request }) => {
    const body = await request.json() as { key: string }
    if (body.key === 'invalid-key') {
      return HttpResponse.json({ message: 'Invalid API key.' }, { status: 422 })
    }
    return HttpResponse.json({ message: 'API key saved.' })
  }),
  http.post('/api/settings/password', async ({ request }) => {
    const body = await request.json() as { currentPassword: string; newPassword: string }
    if (body.currentPassword === 'wrong') {
      return HttpResponse.json({ message: 'Current password is incorrect.' }, { status: 400 })
    }
    return new HttpResponse(null, { status: 200 })
  }),
  http.post('/api/settings/email', () => {
    return new HttpResponse(null, { status: 200 })
  }),
]
```

---

## Environment Availability

Step 2.6: SKIPPED — Phase 2 is code/config changes only. All dependencies (Postgres, mail, WebClient) are already verified operational from Phase 1. TMDB and OMDB validation calls are mocked with WireMock in tests; no real API access needed in CI.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework (BE) | JUnit 5 + Testcontainers + WireMock + GreenMail |
| Framework (FE) | Vitest + @nuxt/test-utils + MSW |
| Config file (BE) | `build.gradle.kts` (`useJUnitPlatform()`) |
| Config file (FE) | `frontend/vitest.config.ts` |
| Quick run (BE) | `cd backend && ./gradlew test --tests "de.moviearchive.settings.*"` |
| Quick run (FE) | `cd frontend && pnpm test:unit --reporter verbose` |
| Full suite (BE) | `cd backend && ./gradlew check` |
| Full suite (FE) | `cd frontend && pnpm test:unit` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SET-01 | Save TMDB key — valid key stored encrypted | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldSaveTmdbKey*"` | No — Wave 0 |
| SET-01 | Save TMDB key — invalid key rejected (WireMock 401) | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldRejectInvalidTmdbKey*"` | No — Wave 0 |
| SET-01 | Get TMDB key — returned as plaintext (D-03) | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldReturnTmdbKeyPlaintext*"` | No — Wave 0 |
| SET-02 | Save OMDB key — same encryption behavior | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldSaveOmdbKey*"` | No — Wave 0 |
| SET-03 | Change password — wrong current password rejected | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldRejectWrongCurrentPassword*"` | No — Wave 0 |
| SET-03 | Change password — all refresh tokens revoked | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldRevokeAllSessionsOnPasswordChange*"` | No — Wave 0 |
| SET-04 | Request email change — confirmation mail sent | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldSendEmailChangeConfirmation*"` | No — Wave 0 |
| SET-04 | Confirm email change — user email updated | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldConfirmEmailChange*"` | No — Wave 0 |
| SET-04 | Confirm email change — new email already taken | Integration | `./gradlew test --tests "*.SettingsIntegrationTest.shouldRejectConflictingEmailChange*"` | No — Wave 0 |
| SET-01 | Frontend: API key form shows inline success on save | Unit (FE) | `pnpm test:unit --reporter verbose settings` | No — Wave 0 |
| SET-03 | Frontend: password change clears auth + redirects | Unit (FE) | `pnpm test:unit --reporter verbose useSettings` | No — Wave 0 |
| SET-05/06 | CSV placeholder buttons visible but disabled | Unit (FE) | `pnpm test:unit --reporter verbose settings` | No — Wave 0 |

### Sampling Rate
- **Per task commit:** Quick run on the new test class only
- **Per wave merge:** Full suite (BE `./gradlew check` + FE `pnpm test:unit`)
- **Phase gate:** Both full suites green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java` — covers SET-01 through SET-04
- [ ] `backend/src/test/java/de/moviearchive/settings/SettingsServiceTest.java` — unit tests for EncryptionService, validator logic
- [ ] `frontend/test/mocks/handlers/settings.ts` — MSW handlers for settings endpoints
- [ ] `frontend/test/unit/pages/settings.spec.ts` — covers inline success/error states
- [ ] `frontend/test/unit/composables/useSettings.spec.ts` — mirrors `useAuth.spec.ts` pattern

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | BCrypt verify before password change; JWT on all `/settings/**` endpoints |
| V3 Session Management | yes | `revokeAllByUserId` after password change; email-change token single-use |
| V4 Access Control | yes | `anyRequest().authenticated()` in SecurityConfig already covers `/settings/**` |
| V5 Input Validation | yes | `@Valid` on all request bodies; `@NotBlank` on key/password/email fields |
| V6 Cryptography | yes | AES-256-GCM for API keys; SHA-256 for email-change token; never CBC or MD5 |

### Known Threat Patterns for Settings Endpoints

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| API key leakage via logs | Information Disclosure | Never log the plaintext key or encrypted blob at INFO level; log only "TMDB key saved for user {id}" |
| Email enumeration via email-change | Information Disclosure | `POST /settings/email` always returns 200 (even if new email conflicts) — conflict reported only at confirm time |
| Token reuse for email change | Tampering | `consumed_at` pattern (single-use) — already established for all token tables |
| Brute-force current-password check | Elevation of Privilege | No dedicated rate limit on `/settings/password` needed for personal app (single user), but BCrypt cost 12 makes brute force expensive |
| CSRF on settings endpoints | Tampering | Stateless JWT + `SameSite=Strict` cookie; CSRF disabled in SecurityConfig — consistent with Phase 1 |
| Plaintext key in transit | Information Disclosure | HTTPS enforced by Caddy in production; dev uses HTTP over localhost only |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | OMDB validation call uses `?i=tt0111161` (Shawshank Redemption) as a known stable IMDB ID | Architecture Patterns / Pattern 3 | If this IMDB ID is removed from OMDB, validation would fail for valid keys. Use any stable IMDB ID. | [ASSUMED] |
| A2 | `PUT /settings/api-keys/{provider}` uses path param `{provider}` that maps to `ApiKeyProvider` enum | Architecture Patterns / Pattern 3 | If the controller uses a separate endpoint per provider, the path structure changes. Either approach works; the planner can choose. | [ASSUMED] |
| A3 | The confirm-email link redirects to `/settings?emailConfirmed=true` (frontend page) via 302 from Spring | Pitfall 7 / SettingsController skeleton | If a JSON 200 is preferred and the link is handled differently on the frontend, the flow changes. The 302-redirect approach avoids a Nuxt page for `/settings/confirm-email`. | [ASSUMED] |

---

## Open Questions

1. **OMDB test IMDB ID for key validation**
   - What we know: OMDB validates keys against a real movie lookup; `?i=tt0111161` is Shawshank Redemption, a stable entry.
   - What's unclear: Whether OMDB free-tier keys correctly respond with `Response: "True"` vs `"False"` in a consistent, testable way.
   - Recommendation: Use `?i=tt0111161&plot=short` as the validation probe. WireMock stub returns `{"Response":"True","Title":"The Shawshank Redemption"}` for valid key, `{"Response":"False","Error":"Invalid API key!"}` for invalid.

2. **Email notification to old address — timing**
   - What we know: The notification to the old address is mentioned in auth-flows.md.
   - What's unclear: Should the old-address notification be sent at `POST /settings/email` (request time) or `GET /settings/confirm-email` (confirm time)?
   - Recommendation: Send at confirm time only. At request time, a user could trigger spam by repeatedly requesting email change. Only confirmed changes warrant notification.

---

## Sources

### Primary (HIGH confidence)
- `backend/src/main/java/de/moviearchive/` — full codebase inspection; entity/repository/service/controller patterns, existing `EmailChangeToken` entity, `TokenUtils`, `revokeAllByUserId`, `AuthService` patterns
- `backend/build.gradle.kts` — all dependency versions verified directly
- `backend/src/main/resources/application.properties` — `encryption.master-key` ENV var confirmed present
- `backend/src/main/resources/db/migration/V3__create_token_tables.sql` — migration pattern confirmed; next is V5
- `backend/src/main/resources/db/migration/V4__add_grace_until_to_refresh_tokens.sql` — confirmed V4 exists, so V5 is next
- `.claude/data-model.md` — `user_api_keys` schema, `email_change_tokens` schema
- `.claude/auth-flows.md` — Settings endpoint list, email-changed template reference
- `.claude/api-contracts.md` — TMDB `/3/configuration` and OMDB `/?i={imdbId}` validation endpoint
- `CLAUDE.md` — AES-256-GCM implementation directives, WebClient mandate, WireMock constraint, test mandate

### Secondary (MEDIUM confidence)
- `frontend/composables/useAuth.ts` — composable pattern for `useSettings` mirror
- `frontend/test/mocks/handlers/auth.ts` — MSW handler pattern for `settingsHandlers`
- `frontend/middleware/auth.global.ts` — `clearAuth()` before redirect requirement (Pitfall 5)

### Tertiary (LOW confidence — assumed)
- OMDB `tt0111161` as stable validation probe — [ASSUMED], not verified against live OMDB

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all from `build.gradle.kts` (no new deps)
- Architecture: HIGH — all patterns directly derived from existing Phase 1 code
- Pitfalls: HIGH — derived from codebase analysis (SecurityConfig, middleware, token patterns) with one MEDIUM (Pitfall 6 WireMock wiring — established pattern from AbstractWireMockTest)
- Encryption: HIGH — from CLAUDE.md directives

**Research date:** 2026-05-16
**Valid until:** 2026-06-16 (stable stack; Spring Boot BOM and JDK crypto are not changing)
