---
phase: 02-settings-api-keys
plan: "02"
subsystem: backend-settings
tags: [backend, settings, api-keys, encryption, aes-gcm, email-change, password-change, wiremock, greenmail]
dependency_graph:
  requires:
    - "02-01"  # test stubs created by plan 01
  provides:
    - PUT /settings/api-keys/{provider} — validate + AES-256-GCM encrypt + upsert
    - GET /settings/api-keys — decrypt + return plaintext (D-03)
    - POST /settings/password — BCrypt verify + revokeAllByUserId
    - POST /settings/email — create EmailChangeToken + send confirm link
    - GET /settings/confirm-email — consume token + update email + notify old address
    - EncryptionService (AES-256-GCM, fresh IV per call)
    - TmdbKeyValidator / OmdbKeyValidator (WebClient, synchronous)
    - V5 Flyway migration (user_api_keys table)
  affects:
    - backend/src/main/java/de/moviearchive/mail/MailService.java (2 new methods)
    - backend/src/main/java/de/moviearchive/config/SecurityConfig.java (permit confirm-email)
    - backend/src/main/java/de/moviearchive/auth/RateLimitService.java (resetAll for tests)
tech_stack:
  added: []
  patterns:
    - AES-256-GCM encrypt with 12-byte fresh IV prepended to ciphertext (Base64 stored in TEXT column)
    - WebClient.block() in Spring MVC Tomcat thread for synchronous key validation
    - wireMock.stubFor() (extension instance) vs static WireMock.stubFor() (avoids port 8080 default)
    - rateLimitService.resetAll() in @BeforeEach to prevent 429 cross-test contamination
    - GET /settings/confirm-email in SecurityConfig permitAll (email link clicked anonymously)
key_files:
  created:
    - backend/src/main/resources/db/migration/V5__create_user_api_keys.sql
    - backend/src/main/java/de/moviearchive/settings/ApiKeyProvider.java
    - backend/src/main/java/de/moviearchive/settings/UserApiKey.java
    - backend/src/main/java/de/moviearchive/settings/UserApiKeyRepository.java
    - backend/src/main/java/de/moviearchive/settings/EncryptionService.java
    - backend/src/main/java/de/moviearchive/settings/TmdbKeyValidator.java
    - backend/src/main/java/de/moviearchive/settings/OmdbKeyValidator.java
    - backend/src/main/java/de/moviearchive/settings/InvalidApiKeyException.java
    - backend/src/main/java/de/moviearchive/settings/SettingsService.java
    - backend/src/main/java/de/moviearchive/settings/SettingsController.java
    - backend/src/main/java/de/moviearchive/settings/dto/SaveApiKeyRequest.java
    - backend/src/main/java/de/moviearchive/settings/dto/ChangePasswordRequest.java
    - backend/src/main/java/de/moviearchive/settings/dto/ChangeEmailRequest.java
    - backend/src/main/resources/templates/mail/email-change-confirm.html
    - backend/src/main/resources/templates/mail/email-change-notification.html
  modified:
    - backend/src/main/java/de/moviearchive/mail/MailService.java
    - backend/src/main/java/de/moviearchive/config/SecurityConfig.java
    - backend/src/main/java/de/moviearchive/auth/RateLimitService.java
    - backend/src/test/resources/application-test.properties
    - backend/src/test/java/de/moviearchive/settings/SettingsServiceTest.java
    - backend/src/test/java/de/moviearchive/settings/SettingsIntegrationTest.java
decisions:
  - "GET /settings/confirm-email added to SecurityConfig permitAll — email link is clicked anonymously from inbox, no auth token available"
  - "wireMock.stubFor() (extension instance) used instead of static WireMock.stubFor() — static client defaults to localhost:8080 causing InvalidInputException when WireMock runs on dynamic port"
  - "RateLimitService.resetAll() added and called in @BeforeEach — 10 login calls per test class exhausted the Bucket4j 10/min bucket, causing 429 false failures"
  - "Notification to old email sent only after confirm (not at request time) — prevents spam via repeated requestEmailChange calls per RESEARCH open question 2"
  - "shouldLogin_returnsAccessTokenAndHttpOnlyCookie in AuthIntegrationTest is a pre-existing failure (asserting Path=/api/auth/refresh but cookie path is /) — confirmed present before this plan, not introduced here, deferred to deferred-items"
metrics:
  duration: "~45 min"
  completed: "2026-05-16"
  tasks_completed: 2
  tasks_total: 2
  files_created: 15
  files_modified: 6
---

# Phase 02 Plan 02: Backend Settings Implementation — Summary

Full backend for Phase 2 settings: AES-256-GCM API key encryption with TMDB/OMDB validation, password change with session revocation, and email change with token confirmation flow. All 13 integration test stubs from plan 02-01 now pass.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Backend core — migration, encryption, entity, validators, service | 4bdf577 | V5 migration, EncryptionService, TmdbKeyValidator, OmdbKeyValidator, SettingsService, 3 DTOs, InvalidApiKeyException, 2 mail templates, MailService (2 new methods), application-test.properties, SettingsServiceTest |
| 2 | Backend controller and integration tests | dcaf579 | SettingsController, SecurityConfig, RateLimitService, SettingsIntegrationTest |

## What Was Built

**Task 1 — Core infrastructure:**
- `V5__create_user_api_keys.sql`: `user_api_keys` table with `UNIQUE(user_id, provider)` constraint and `ON DELETE CASCADE` FK to users
- `EncryptionService`: AES-256-GCM, fresh 12-byte IV per `encrypt()` call, IV prepended to ciphertext, stored as Base64 TEXT
- `TmdbKeyValidator`: WebClient GET `/3/configuration?api_key=`, returns false on 401, re-throws on other errors
- `OmdbKeyValidator`: WebClient GET `/?apikey=&i=tt0111161`, checks `Response` field in JSON body
- `SettingsService`: full business logic for all 5 endpoints; upsert pattern for API keys; BCrypt verify + revokeAllByUserId for password change; enumeration-safe email change (always 200, check uniqueness at confirm time)
- `MailService` additions: `sendEmailChangeConfirmation` (link to `/api/settings/confirm-email` for Caddy routing per Pitfall 7), `sendEmailChangeNotification`
- `SettingsServiceTest`: 6 tests — 2 EncryptionService round-trip/fresh-IV tests pass; 4 validator tests delegate to integration tests with documented reasoning

**Task 2 — Controller and integration:**
- `SettingsController`: 5 endpoints + 8 exception handlers following AuthController pattern
- `SecurityConfig` fix: `GET /settings/confirm-email` added to `permitAll` (email links clicked anonymously)
- `RateLimitService.resetAll()`: clears buckets between tests to prevent 429 cross-contamination
- `SettingsIntegrationTest`: all 13 stubs implemented; uses `wireMock.stubFor()` (extension instance); GreenMail assertions for email verification; rate limiter reset in `@BeforeEach`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] GET /settings/confirm-email requires no authentication**
- **Found during:** Task 2 — integration test returned 403
- **Issue:** `anyRequest().authenticated()` blocked anonymous access to `GET /settings/confirm-email`, but the confirm link is clicked from the user's email client with no auth token
- **Fix:** Added `/settings/confirm-email` to `permitAll` in `SecurityConfig`
- **Files modified:** `backend/src/main/java/de/moviearchive/config/SecurityConfig.java`
- **Commit:** dcaf579

**2. [Rule 2 - Missing critical functionality] Rate limiter needs reset between integration tests**
- **Found during:** Task 2 — `shouldSaveOmdbKey_whenKeyIsValid` and `shouldNotifyOldAddress_afterEmailChangeConfirmed` returned 429 when run after other tests that called `/auth/login`
- **Issue:** `RateLimitService` uses an in-memory `ConcurrentHashMap` that persists across tests in the same JVM. After ~10 login calls from `127.0.0.1`, the Bucket4j bucket was exhausted.
- **Fix:** Added `resetAll()` method to `RateLimitService`; called in `@BeforeEach cleanDb()` of `SettingsIntegrationTest`
- **Files modified:** `backend/src/main/java/de/moviearchive/auth/RateLimitService.java`, `SettingsIntegrationTest.java`
- **Commit:** dcaf579

**3. [Rule 1 - Bug] Static WireMock.stubFor() connects to localhost:8080 instead of dynamic port**
- **Found during:** Task 2 — all WireMock tests threw `InvalidInputException` with "status code 403 for http://localhost:8080/__admin/mappings"
- **Issue:** Static import `import static com.github.tomakehurst.wiremock.client.WireMock.stubFor` uses a global static WireMock client defaulting to port 8080. The `WireMockExtension` instance runs on a dynamic port.
- **Fix:** Replaced all `stubFor(...)` with `wireMock.stubFor(...)` (extension instance). Removed conflicting static import of WireMock's `get` method; used fully qualified `com.github.tomakehurst.wiremock.client.WireMock.get(...)` in stub helpers to avoid clash with `MockMvcRequestBuilders.get`.
- **Files modified:** `SettingsIntegrationTest.java`
- **Commit:** dcaf579

## Known Stubs

None. All production code is fully implemented. The 4 validator unit tests in `SettingsServiceTest` that defer to integration tests are documented design choices, not stubs — the integration tests provide full WireMock coverage.

## Deferred Items

- `AuthIntegrationTest.shouldLogin_returnsAccessTokenAndHttpOnlyCookie` — pre-existing failure asserting `Path=/api/auth/refresh` on the refresh cookie, but `AuthService` sets `path("/")`. Confirmed failing before this plan. Not introduced here.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: access-control | SecurityConfig.java | `GET /settings/confirm-email` moved to `permitAll` — this is intentional (email link clicked anonymously) and covered by T-02-02-06 (token single-use) and T-02-02-08 (re-check uniqueness at confirm time). No new attack surface beyond what the threat model accounts for. |

## Self-Check: PASSED

**Files exist:**
- FOUND: backend/src/main/resources/db/migration/V5__create_user_api_keys.sql
- FOUND: backend/src/main/java/de/moviearchive/settings/EncryptionService.java
- FOUND: backend/src/main/java/de/moviearchive/settings/SettingsController.java
- FOUND: backend/src/main/java/de/moviearchive/settings/SettingsService.java
- FOUND: backend/src/main/java/de/moviearchive/settings/TmdbKeyValidator.java
- FOUND: backend/src/main/java/de/moviearchive/settings/OmdbKeyValidator.java
- FOUND: backend/src/main/resources/templates/mail/email-change-confirm.html
- FOUND: backend/src/main/resources/templates/mail/email-change-notification.html

**Commits exist:**
- FOUND: 4bdf577 — feat(02-02): backend core — migration, encryption, entity, validators, service
- FOUND: dcaf579 — feat(02-02): settings controller and integration tests
