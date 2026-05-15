---
phase: 01-authentication
plan: "03"
subsystem: frontend-auth
tags: [frontend, nuxt, pinia, vue3, tailwindcss, vitest, msw, auth]
dependency_graph:
  requires: ["01-01", "01-02"]
  provides:
    - "Pinia auth store (in-memory access token, refresh, setAuth, clearAuth)"
    - "Global route middleware (cookie presence check)"
    - "Client plugin (silent refresh on init)"
    - "useAuth composable (login, signup, logout, verifyEmail, forgotPassword, resetPassword)"
    - "6 auth UI components (AuthCard, FormField, InputText, ButtonPrimary, FormErrorBanner, SpinnerIcon)"
    - "6 auth pages (login, signup, verify-email-sent, verify-email, forgot-password, reset-password)"
    - "MSW auth handlers for all 9 auth endpoints"
  affects:
    - "all authenticated routes (protected by auth.global.ts)"
    - "plan-04 (settings page needs authenticated user via this store)"
tech_stack:
  added: []
  patterns:
    - "Pinia composition-API store with in-memory access token (no localStorage)"
    - "Global Nuxt middleware with useCookie presence check only"
    - "defineNuxtPlugin async client plugin for silent refresh on init"
    - "vi.mock('#app/composables/router') to mock navigateTo in Nuxt test environment"
    - "Option D color palette overrides in :root CSS custom properties (raw hex)"
key_files:
  created:
    - frontend/assets/css/main.css (modified: Option D :root token overrides)
    - frontend/stores/auth.ts
    - frontend/middleware/auth.global.ts
    - frontend/plugins/auth.client.ts
    - frontend/composables/useAuth.ts
    - frontend/components/AuthCard.vue
    - frontend/components/FormField.vue
    - frontend/components/InputText.vue
    - frontend/components/ButtonPrimary.vue
    - frontend/components/FormErrorBanner.vue
    - frontend/components/SpinnerIcon.vue
    - frontend/pages/login.vue
    - frontend/pages/signup.vue
    - frontend/pages/verify-email-sent.vue
    - frontend/pages/verify-email.vue
    - frontend/pages/forgot-password.vue
    - frontend/pages/reset-password.vue
    - frontend/test/mocks/handlers/auth.ts
    - frontend/test/mocks/handlers.ts (modified: authHandlers imported)
    - frontend/test/unit/stores/auth.spec.ts
    - frontend/test/unit/composables/useAuth.spec.ts
    - frontend/test/unit/middleware/auth.spec.ts
    - frontend/test/unit/plugins/auth.spec.ts
    - frontend/test/unit/pages/login.spec.ts
    - frontend/test/unit/pages/signup.spec.ts
    - frontend/test/unit/pages/forgot-password.spec.ts
    - frontend/test/unit/pages/reset-password.spec.ts
    - frontend/test/unit/pages/verify-email.spec.ts
  modified:
    - frontend/assets/css/main.css
    - frontend/test/mocks/handlers.ts
decisions:
  - "navigateTo mocked via vi.mock('#app/composables/router') not vi.stubGlobal — Nuxt test env resolves auto-imports from the module, not the global scope"
  - "useAuth composable owns navigateTo calls; pages delegate entirely to composable — keeps pages thin and composable fully testable"
  - "Worktree rebased to 48362a1 via git reset --soft before execution — old branch had diverged from target base"
metrics:
  duration: "~7 minutes"
  completed: "2026-05-15T18:27:00Z"
  tasks_completed: 3
  tasks_total: 3
  files_created: 27
  files_modified: 2
---

# Phase 01 Plan 03: Frontend Auth Layer Summary

**One-liner:** Complete Nuxt 3 frontend auth layer — Pinia in-memory token store, global cookie-presence middleware, silent-refresh plugin, useAuth composable, 6 UI components, 6 auth pages with Option D editorial palette, and 54 passing Vitest tests.

## What Was Built

### Task 1: CSS tokens + Pinia store + middleware + plugin + MSW handlers

| Artifact | Description |
|----------|-------------|
| `frontend/assets/css/main.css` | `:root` overridden with Option D hex values (warm off-white `#FAF7F2` + terracotta `#C84B31`); `.dark {}` untouched |
| `frontend/stores/auth.ts` | Pinia composition store: `accessToken`/`userEmail` refs, `setAuth`, `clearAuth`, `isAuthenticated` computed, `refresh()` — zero localStorage calls |
| `frontend/middleware/auth.global.ts` | Server-side cookie presence check via `useCookie('refresh_token')` — redirects to `/login` when absent on non-public routes |
| `frontend/plugins/auth.client.ts` | `defineNuxtPlugin` calls `authStore.refresh()` on init; catches all errors silently (D-05) |
| `frontend/test/mocks/handlers/auth.ts` | MSW handlers for all 9 auth endpoints: login (200/401/403), signup (201/409), refresh, logout, verify-email (200/400), forgot-password, reset-password |
| `frontend/test/mocks/handlers.ts` | Updated to import and spread `authHandlers` |

Tests: 5 (store) + 8 (middleware) + 3 (plugin) = **16 tests passing**

### Task 2: Auth UI components + useAuth composable

| Component | Description |
|-----------|-------------|
| `AuthCard.vue` | Centered card layout: full-viewport, app name above card, heading + subtext props, default slot |
| `FormField.vue` | Label + slot + optional error `<p role="alert">` with terracotta `#7A3520` color |
| `InputText.vue` | Native `<input>` with `aria-invalid`, error border `border-[#7A3520]`, `rounded-none` |
| `ButtonPrimary.vue` | Full-width h-11 button, `bg-primary`, spinner via SpinnerIcon, disabled+loading states |
| `FormErrorBanner.vue` | `role="alert"` div with left `border-l-2 border-[#7A3520]` — API error display only |
| `SpinnerIcon.vue` | Lucide `Loader2` with `animate-spin` |
| `useAuth.ts` | `login`, `signup`, `logout`, `verifyEmail`, `forgotPassword`, `resetPassword` — all with `credentials:'include'`; signup has no `setAuth` (D-09) |

Tests: **6 tests passing**

### Task 3: 6 auth pages + page tests

| Page | Key Behaviors |
|------|---------------|
| `/login` | 401 → "Invalid email or password.", 403 → "Please verify your email...", 429 → "Too many attempts. Try again in X seconds." (Retry-After), error clears on input |
| `/signup` | Client-side email/password validation, 409 → "An account with this email already exists.", no auto-login after signup |
| `/verify-email-sent` | Informational only — "Check your inbox" |
| `/verify-email` | `onMounted` calls `verifyEmail(token)`, shows loading/success/error states |
| `/forgot-password` | Any 200 → success state (enumeration protection), 429 → Retry-After message |
| `/reset-password` | Password mismatch/length validation, 400 expired/used token messages, success CTA |

Tests: 7 + 6 + 4 + 5 + 4 = **26 tests passing**

## Test Results

```
Test Files  11 passed (11)
      Tests  54 passed (54)
   Duration  ~1.83s
```

Exit code: 0. All 54 tests pass.

| File | Tests |
|------|-------|
| `test/unit/stores/auth.spec.ts` | 5 |
| `test/unit/middleware/auth.spec.ts` | 8 |
| `test/unit/plugins/auth.spec.ts` | 3 |
| `test/unit/composables/useAuth.spec.ts` | 6 |
| `test/unit/pages/login.spec.ts` | 7 |
| `test/unit/pages/signup.spec.ts` | 6 |
| `test/unit/pages/forgot-password.spec.ts` | 4 |
| `test/unit/pages/reset-password.spec.ts` | 5 |
| `test/unit/pages/verify-email.spec.ts` | 4 |
| `test/unit/smoke.spec.ts` | 4 |
| `test/unit/components/AppHome.spec.ts` | 2 |

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | `2fdcdc1` | `feat(01-03): CSS tokens + Pinia auth store + middleware + plugin + MSW handlers` |
| Task 2 | `d6b2cd5` | `feat(01-03): auth UI components + useAuth composable + composable tests` |
| Task 3 | `30b9d6a` | `feat(01-03): 6 auth pages + page tests` |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] vi.stubGlobal('navigateTo') bypassed by Nuxt auto-import resolution**
- **Found during:** Task 2 (useAuth composable tests failing on navigateTo assertions)
- **Issue:** The `nuxt` Vitest environment resolves `navigateTo` from `#app/composables/router` as an ES module import, not from the global scope. `vi.stubGlobal('navigateTo', mockFn)` had no effect.
- **Fix:** Replaced `vi.stubGlobal` with `vi.mock('#app/composables/router', ...)` which patches the module export directly. Applied to all composable and page test files.
- **Files modified:** `useAuth.spec.ts`, `login.spec.ts`, `signup.spec.ts`, `forgot-password.spec.ts`, `reset-password.spec.ts`, `verify-email.spec.ts`
- **Commit:** `d6b2cd5`, `30b9d6a`

**2. [Rule 3 - Blocking] Worktree branch diverged from target base commit**
- **Found during:** Pre-execution branch check
- **Issue:** Worktree was on `f9f273c` (old history), not based on `48362a1` (target). `git reset --soft` + `git checkout --` restored clean state at the correct base.
- **Fix:** `git reset --soft 48362a13200be291e750f3a04128e3bbf801bc09` then `git checkout -- .`
- **No code impact:** No application files were affected.

## Known Stubs

None. All plan-specified functionality is fully implemented and tested.

## Threat Flags

No new threat surface beyond the plan's threat model (T-1-FE-01 through T-1-FE-07). All trust boundaries and mitigations from the threat model were implemented as specified:

- T-1-FE-01: `stores/auth.ts` has zero `localStorage` references (verified by grep)
- T-1-FE-04: `navigateTo('/login')` uses hardcoded path only — no `?redirect=` parameter accepted

## Self-Check: PASSED

Files verified present:
- `frontend/stores/auth.ts` — FOUND
- `frontend/middleware/auth.global.ts` — FOUND
- `frontend/plugins/auth.client.ts` — FOUND
- `frontend/composables/useAuth.ts` — FOUND
- `frontend/components/AuthCard.vue` — FOUND
- `frontend/components/FormField.vue` — FOUND
- `frontend/components/InputText.vue` — FOUND
- `frontend/components/ButtonPrimary.vue` — FOUND
- `frontend/components/FormErrorBanner.vue` — FOUND
- `frontend/components/SpinnerIcon.vue` — FOUND
- `frontend/pages/login.vue` — FOUND
- `frontend/pages/signup.vue` — FOUND
- `frontend/pages/verify-email-sent.vue` — FOUND
- `frontend/pages/verify-email.vue` — FOUND
- `frontend/pages/forgot-password.vue` — FOUND
- `frontend/pages/reset-password.vue` — FOUND
- `frontend/test/mocks/handlers/auth.ts` — FOUND

Commits verified:
- `2fdcdc1` — FOUND
- `d6b2cd5` — FOUND
- `30b9d6a` — FOUND
