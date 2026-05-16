---
phase: 02-settings-api-keys
verified: 2026-05-16T11:59:44Z
status: verified
score: 5/5 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Log in, navigate to /settings — confirm Settings link is visible in AppNav"
    expected: "Settings text link appears between user email and Sign out button"
    why_human: "Visual rendering of conditional v-if cannot be asserted by unit tests without mountSuspended"
  - test: "Enter a valid TMDB API key, click Save — confirm inline 'Saved' text appears"
    expected: "Inline 'Saved' paragraph appears below the TMDB key field, no page reload"
    why_human: "D-06 inline success state requires live DOM interaction to verify rendering"
  - test: "Enter an invalid TMDB API key (not accepted by TMDB /3/configuration), click Save"
    expected: "Inline error message appears below TMDB key field, no toast"
    why_human: "D-10 inline error requires visual inspection of error placement"
  - test: "Submit email change form — confirm inline 'Check your inbox' message appears below email field"
    expected: "Success message renders below the email input, email field resets, no redirect"
    why_human: "D-07 inline message state requires live page interaction"
  - test: "Submit password change with wrong current password — confirm inline error appears"
    expected: "FormErrorBanner shows 'Current password is incorrect.' within the form, no toast"
    why_human: "D-10 inline error placement requires visual verification"
  - test: "Navigate to /settings when logged out"
    expected: "auth.global.ts middleware redirects to /login immediately"
    why_human: "Global middleware behavior requires a browser session to verify"
---

# Phase 02: Settings & API Keys — Verification Report

**Phase Goal:** Users can manage API keys (TMDB required, OMDB optional), change their password, and change their email — all persisted securely in the database.
**Verified:** 2026-05-16T11:59:44Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | User can save and update a TMDB API key (stored AES-256-GCM encrypted, displayed in plaintext per D-03) | VERIFIED | `EncryptionService.java` uses AES/GCM/NoPadding with fresh 12-byte IV per call; `SettingsService.saveApiKey()` validates via `TmdbKeyValidator`, upserts via `findByUserIdAndProvider`, stores `encryptionService.encrypt(rawKey)`; `getApiKeys()` decrypts and returns plaintext; 13 integration tests pass (0 skipped) |
| 2 | User can optionally save and update an OMDB API key with same encryption behavior | VERIFIED | `OmdbKeyValidator` checks `Response=True` field; `SettingsService` handles OMDB provider identically; integration tests cover valid/invalid OMDB key scenarios |
| 3 | User can change password by providing current password; all existing sessions are invalidated | VERIFIED | `SettingsService.changePassword()` calls `passwordEncoder.matches()` then `refreshTokenRepository.revokeAllByUserId()`; returns 400 on wrong password; integration test `shouldRevokeAllRefreshTokens_onPasswordChange` passes |
| 4 | User can change email address; a verification link is sent to the new address and the old address is notified after confirmation | VERIFIED | `requestEmailChange()` sends `sendEmailChangeConfirmation` to new address; `confirmEmailChange()` consumes token, updates email, calls `sendEmailChangeNotification` to old address; `GET /settings/confirm-email` redirects to `/settings?emailConfirmed=true`; integration tests for both mail sends pass |
| 5 | Settings page has a visible Import & Export section (CSV buttons disabled — placeholder for post-Phase 3) | VERIFIED | `settings.vue` has `<section id="import-export">` with two `<ButtonPrimary :disabled="true">` buttons and "Coming soon — available after your first films are saved." text |

**Score:** 5/5 truths verified

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|---------|
| SET-01 | 02-02, 02-03 | TMDB API key save/update, AES-256-GCM encrypted, plaintext display | SATISFIED | `EncryptionService` + `SettingsController PUT /api-keys/tmdb` + `settings.vue` type="text" input + `onMounted` loadApiKeys |
| SET-02 | 02-02, 02-03 | OMDB API key save/update, optional, same encryption | SATISFIED | Identical code path with `ApiKeyProvider.OMDB`; OMDB input in `settings.vue` |
| SET-03 | 02-02, 02-03 | Password change with current password verification; all sessions revoked | SATISFIED | `SettingsService.changePassword()` + `refreshTokenRepository.revokeAllByUserId()` + `useSettings.changePassword()` calls `clearAuth()` before `navigateTo('/login')` |
| SET-04 | 02-02, 02-03 | Email change via token link to new address; old address notified after confirm | SATISFIED | Full token flow in `SettingsService`; GreenMail integration tests verify both emails |
| SET-05 | 02-03 | CSV export (deferred — placeholder UI only) | SATISFIED (placeholder) | Per D-08 in CONTEXT.md: disabled Export CSV button with "Coming soon" note. No backend. Intentional per phase scope. |
| SET-06 | 02-03 | CSV import (deferred — placeholder UI only) | SATISFIED (placeholder) | Per D-08 in CONTEXT.md: disabled Import CSV button with "Coming soon" note. No backend. Intentional per phase scope. |

**Note on SET-05/SET-06:** REQUIREMENTS.md maps these to Phase 2, but CONTEXT.md explicitly defers full implementation to post-Phase 3 (when movie schema is stable). The phase deliverable for SET-05/SET-06 is the UI placeholder section, not a working CSV endpoint. No later roadmap phase has a success criterion for CSV, meaning this is an open v1 item outside the current milestone — the placeholder is the intentional Phase 2 artifact.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/resources/db/migration/V5__create_user_api_keys.sql` | user_api_keys DDL with UNIQUE(user_id, provider) | VERIFIED | DDL exists, `CONSTRAINT uq_uak_user_provider UNIQUE (user_id, provider)` confirmed |
| `backend/src/main/java/de/moviearchive/settings/EncryptionService.java` | AES-256-GCM encrypt/decrypt | VERIFIED | `Cipher.getInstance("AES/GCM/NoPadding")`, fresh `new SecureRandom().nextBytes(iv)` per call, IV prepended, Base64 stored |
| `backend/src/main/java/de/moviearchive/settings/SettingsController.java` | 5 REST endpoints | VERIFIED | PUT /api-keys/{provider}, GET /api-keys, POST /password, POST /email, GET /confirm-email all present with 8 exception handlers |
| `backend/src/main/java/de/moviearchive/settings/SettingsService.java` | Full business logic | VERIFIED | All 5 operations implemented: saveApiKey, getApiKeys, changePassword, requestEmailChange, confirmEmailChange |
| `backend/src/main/resources/templates/mail/email-change-confirm.html` | Thymeleaf confirm template | VERIFIED | File exists, contains `th:href="${confirmUrl}"` |
| `backend/src/main/resources/templates/mail/email-change-notification.html` | Thymeleaf notification template | VERIFIED | File exists |
| `frontend/pages/settings.vue` | Settings page with 3 anchor sections | VERIFIED | sections id="account", id="api-keys", id="import-export" all present |
| `frontend/composables/useSettings.ts` | saveApiKey, loadApiKeys, changePassword, changeEmail | VERIFIED | All 4 functions implemented with correct $fetch calls |
| `frontend/components/AppNav.vue` | Settings link visible when logged in | VERIFIED | `<NuxtLink v-if="authStore.accessToken" to="/settings">` present |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `SettingsController PUT /api-keys/{provider}` | `TmdbKeyValidator.validate()` / `OmdbKeyValidator.validate()` | switch on provider, `.validate(rawKey)` | WIRED | `SettingsService.saveApiKey()` calls validator before encrypting |
| `SettingsService.saveApiKey()` | `userApiKeyRepository.findByUserIdAndProvider()` | ifPresentOrElse upsert | WIRED | Lines 80-87 in SettingsService.java |
| `SettingsService.changePassword()` | `refreshTokenRepository.revokeAllByUserId()` | direct call | WIRED | Line 120 in SettingsService.java |
| `GET /settings/confirm-email` | Spring 302 Location header | `ResponseEntity.status(302).header("Location", ...)` | WIRED | Lines 76-77 in SettingsController.java, `emailConfirmed=true` confirmed |
| `frontend/pages/settings.vue` | `frontend/composables/useSettings.ts` | `const { saveApiKey, loadApiKeys, changePassword, changeEmail } = useSettings()` | WIRED | Line 8 in settings.vue |
| `frontend/pages/settings.vue` | `frontend/stores/auth.ts` | `authStore.clearAuth()` before `navigateTo('/login')` | WIRED | Line 27-28 in useSettings.ts — clearAuth inside changePassword |
| `frontend/components/AppNav.vue` | `frontend/stores/auth.ts` | `v-if="authStore.accessToken"` on Settings NuxtLink | WIRED | Lines 25-26 in AppNav.vue |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| `settings.vue` (API Keys section) | `tmdbKey`, `omdbKey` | `onMounted` → `loadApiKeys()` → `GET /api/settings/api-keys` → `SettingsService.getApiKeys()` → `encryptionService.decrypt(k.getEncryptedKey())` | Yes — DB query via `userApiKeyRepository.findByUserIdAndProvider()` | FLOWING |
| `settings.vue` (email change success) | `emailChangeSuccess` | `handleChangeEmail()` sets `true` on successful `POST /api/settings/email` | Yes — triggered by real API response | FLOWING |
| `settings.vue` (inline saved state) | `tmdbSaved`, `omdbSaved` | `handleSaveTmdb/Omdb()` sets `true` on successful `PUT /api/settings/api-keys/{provider}` | Yes — triggered by real API response | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| SettingsIntegrationTest (13 tests) | `./gradlew test --tests "de.moviearchive.settings.SettingsIntegrationTest"` | 13 passed, 0 skipped, 0 failed | PASS |
| SettingsServiceTest (6 tests) | `./gradlew test --tests "de.moviearchive.settings.SettingsServiceTest"` | 6 passed, 0 skipped, 0 failed | PASS |
| Frontend unit tests (71 total) | `pnpm test` | 71 passed, 13 files, exit 0 | PASS |
| useSettings.spec.ts (7 tests) | included in pnpm test | 7 passed, 0 todos | PASS |
| settings.spec.ts (8 tests) | included in pnpm test | 8 passed, 0 todos | PASS |
| Java compilation | `./gradlew compileJava` | BUILD SUCCESSFUL | PASS |

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `frontend/pages/settings.vue` | "Coming soon" text and disabled CSV buttons | Info | Intentional per D-08; not a stub — explicit phase deliverable for SET-05/SET-06 |

No blockers or warnings found. The "Coming soon" pattern is the specified deliverable for the Import & Export placeholder, not an unintended stub.

### Human Verification Required

#### 1. Settings link visibility in AppNav

**Test:** Log in with valid credentials. Observe the top navigation bar.
**Expected:** A "Settings" text link appears between the user email display and the Sign out button.
**Why human:** `v-if="authStore.accessToken"` conditional rendering cannot be asserted without a running browser session; the unit tests only verify the AppNav component is defined, not its rendered DOM state.

#### 2. TMDB key save — inline Saved state (D-06)

**Test:** Navigate to /settings. Enter any text in the TMDB API key field. Click Save (assumes a WireMock/backend stub returns 200 or a real valid key in a local dev environment).
**Expected:** An inline "Saved" text appears directly below the TMDB key field without a page reload or toast notification.
**Why human:** D-06 inline success state requires live DOM rendering; unit tests assert composable throw/resolve behavior, not component rendering of `v-if="tmdbSaved"`.

#### 3. Invalid key — inline error (D-10)

**Test:** Enter an API key that your backend rejects (invalid key). Click Save.
**Expected:** An inline error message appears below the TMDB/OMDB key field (not a toast). No redirect.
**Why human:** Inline error placement (via `:error="tmdbError"` prop on FormField) requires visual inspection.

#### 4. Email change — inline inbox message (D-07)

**Test:** Submit the email change form with a new email address.
**Expected:** The page stays on /settings. Below the email field, "Check your inbox — click the link to confirm your new address." appears. The email input clears.
**Why human:** Inline message state requires live page interaction and visual confirmation.

#### 5. Wrong password — inline error (D-10)

**Test:** Enter the wrong current password and submit the Change Password form.
**Expected:** "Current password is incorrect." appears as an inline FormErrorBanner within the form. No toast. No redirect.
**Why human:** FormErrorBanner placement and error wording requires visual inspection.

#### 6. Unauthenticated access to /settings

**Test:** Open an incognito window (no session) and navigate directly to /settings.
**Expected:** auth.global.ts middleware immediately redirects to /login.
**Why human:** Global Nuxt middleware behavior requires a real browser session to verify end-to-end.

### Gaps Summary

No gaps found. All 5 roadmap success criteria are verified against the codebase. All backend tests (13 integration + 6 unit) and frontend tests (71 total, 0 todos remaining) pass. Key links are wired. Data flows from real DB queries through the API to the UI.

The 6 items above require human verification because they involve visual rendering and browser session behavior that cannot be asserted programmatically through the existing unit test suite.

---

_Verified: 2026-05-16T11:59:44Z_
_Verifier: Claude (gsd-verifier)_
