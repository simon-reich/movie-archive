---
phase: 02-settings-api-keys
plan: "03"
subsystem: frontend-settings
tags: [frontend, settings, api-keys, composable, nuxt, vue, tailwind, inline-feedback]
dependency_graph:
  requires:
    - "02-01"  # test stubs (useSettings.spec.ts, settings.spec.ts todos)
    - "02-02"  # backend endpoints (PUT /settings/api-keys, POST /settings/password, etc.)
  provides:
    - useSettings composable (saveApiKey, loadApiKeys, changePassword, changeEmail)
    - settings.vue page with three anchor sections (Account, API Keys, Import & Export)
    - AppNav settings link visible when logged in (D-02)
    - All 15 frontend test todos from plan 02-01 now passing
  affects:
    - frontend/components/AppNav.vue (settings link added)
tech_stack:
  added: []
  patterns:
    - useSettings composable mirrors useAuth — $fetch + navigateTo + authStore.clearAuth() pattern
    - changePassword encapsulates clearAuth() + navigateTo('/login') internally (D-05, Pitfall 5)
    - Inline success state via ref (tmdbSaved, omdbSaved, emailChangeSuccess) — reset on input watch (D-06, D-07)
    - onMounted loadApiKeys() with keysLoading guard — disabled inputs with placeholder "Loading..."
    - v-if="authStore.accessToken" guards Settings NuxtLink in AppNav (D-02, T-02-03-05)
key_files:
  created:
    - frontend/pages/settings.vue
  modified:
    - frontend/composables/useSettings.ts
    - frontend/components/AppNav.vue
    - frontend/test/unit/composables/useSettings.spec.ts
    - frontend/test/unit/pages/settings.spec.ts
decisions:
  - "changePassword calls authStore.clearAuth() inside useSettings before navigateTo('/login') — composable owns the redirect, not the page (D-05, Pitfall 5)"
  - "Settings NuxtLink uses v-if='authStore.accessToken' (not isAuthenticated computed) — consistent with AppNav's existing accessToken reference pattern"
  - "tmdbSaved/omdbSaved are ref booleans, reset by watch on tmdbKey/omdbKey — no timer needed, resets on any user edit (D-06)"
  - "Settings page uses plain max-w-2xl layout (not AuthCard centered layout) — settings is an authenticated page, not an auth flow page"
  - "ButtonPrimary type='button' with :disabled='true' for Export/Import CSV — static disabled, not loading state (D-08)"
metrics:
  duration: "~15 min"
  completed: "2026-05-16T13:53:08Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 4
---

# Phase 02 Plan 03: Frontend Settings Page — Summary

Settings page UI with useSettings composable and AppNav integration. Users can configure TMDB/OMDB API keys (plaintext, loaded on mount, inline Saved state on success), change password (client validation, clearAuth before redirect), change email (inline inbox message), and see the CSV placeholder section. All 15 frontend test todos from plan 02-01 now pass.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | useSettings composable and AppNav settings link | 76f9c57 | useSettings.ts, AppNav.vue, useSettings.spec.ts |
| 2 | Settings page and page tests | e2beca1 | settings.vue, settings.spec.ts |

## What Was Built

**Task 1 — useSettings composable and AppNav:**
- `useSettings.ts`: Full implementation replacing the plan 02-01 stub. Four exported functions: `saveApiKey` (PUT with provider path), `loadApiKeys` (GET, returns `{tmdb, omdb}`), `changePassword` (POST + clearAuth + navigateTo('/login')), `changeEmail` (POST).
- `AppNav.vue`: Settings `NuxtLink` added between email span and Sign out button, rendered conditionally via `v-if="authStore.accessToken"` (D-02, T-02-03-05).
- `useSettings.spec.ts`: All 7 `it.todo` stubs replaced with passing tests covering PUT tmdb, PUT omdb, 422 rejection, GET loadApiKeys, changePassword + navigateTo, 400 rejection, and changeEmail.

**Task 2 — Settings page and page tests:**
- `settings.vue`: Single-page layout (`max-w-2xl mx-auto px-4 py-8`) with three `<section>` elements anchored by `id` attributes.
  - **Account section**: Email change form (inline "Check your inbox" on success, D-07; FormErrorBanner on error) + Password change form (client-side validation for length < 8 and mismatch, FormErrorBanner on 400).
  - **API Keys section**: TMDB and OMDB `type="text"` inputs (D-03, plaintext) pre-filled via `onMounted` loadApiKeys. Inline "Saved" `<p>` on success (D-06). Inline error via FormField `:error` prop on 422. `watch()` resets saved/error state on input change.
  - **Import & Export section**: Two `ButtonPrimary` with `:disabled="true"` + "Coming soon" note (D-08).
- `settings.spec.ts`: All 8 `it.todo` stubs replaced with passing tests (module exports + composable behavior assertions consistent with login.spec.ts pattern).

**Test result:** 71 tests pass across 13 test files, 0 todos, exit 0.

## Deviations from Plan

None — plan executed exactly as written. The plan's test code for `settings.spec.ts` was used verbatim (composable-level assertions matching login.spec.ts depth). The settings page structure and reactive state matched the plan's specification exactly.

## Known Stubs

None. All production code is fully implemented. The Import & Export disabled buttons are intentional placeholders per D-08 (SET-05/06 deferred to post-Phase 3), not stubs — the plan explicitly requires this UI state.

## Threat Flags

None. No new network endpoints introduced. The settings page is protected by the existing `auth.global.ts` middleware (T-02-03-03). The AppNav settings link is guarded by `v-if="authStore.accessToken"` (T-02-03-05). clearAuth before navigateTo prevents the redirect loop described in T-02-03-02/Pitfall 5.

## Self-Check: PASSED

**Files exist:**
- FOUND: frontend/pages/settings.vue
- FOUND: frontend/composables/useSettings.ts
- FOUND: frontend/components/AppNav.vue
- FOUND: frontend/test/unit/composables/useSettings.spec.ts
- FOUND: frontend/test/unit/pages/settings.spec.ts

**Commits exist:**
- FOUND: 76f9c57 — feat(02-03): useSettings composable and AppNav settings link
- FOUND: e2beca1 — feat(02-03): settings page and page tests

**Acceptance criteria verified:**
- `find frontend/composables -name "useSettings.ts"` → 1 result
- `grep "clearAuth" frontend/composables/useSettings.ts` → matches
- `grep "navigateTo.*login" frontend/composables/useSettings.ts` → matches
- `grep 'to="/settings"' frontend/components/AppNav.vue` → matches
- `grep -c "it.todo" frontend/test/unit/composables/useSettings.spec.ts` → 0
- `grep 'id="api-keys"' frontend/pages/settings.vue` → matches
- `grep 'id="import-export"' frontend/pages/settings.vue` → matches
- `grep -c 'type="text"' frontend/pages/settings.vue` → 2 (TMDB + OMDB)
- `grep "tmdbSaved\|omdbSaved" frontend/pages/settings.vue` → matches
- `grep "emailChangeSuccess" frontend/pages/settings.vue` → matches
- `grep ":disabled.*true" frontend/pages/settings.vue` → matches (Export CSV + Import CSV)
- `grep "Coming soon" frontend/pages/settings.vue` → matches
- `grep -c "it.todo" frontend/test/unit/pages/settings.spec.ts` → 0
- `pnpm test` → 71 passed, exit 0
