---
phase: 01-authentication
plan: 01
subsystem: auth-test-scaffolding
tags: [test-stubs, wave-0, backend, frontend, vitest, junit5]
dependency_graph:
  requires: []
  provides:
    - backend test stubs (AuthControllerTest, AuthIntegrationTest, AuthServiceTest, JwtServiceTest, MailServiceTest, RefreshTokenServiceTest)
    - frontend test stubs (auth store, useAuth, middleware, plugin, all 5 auth pages)
  affects:
    - plan-02 (backend production code — stubs must exist first)
    - plan-03 (frontend production code — stubs must exist first)
tech_stack:
  added: []
  patterns:
    - "@Disabled (JUnit 5) for Wave 0 backend stubs"
    - "test.skip (Vitest) for Wave 0 frontend stubs"
key_files:
  created:
    - backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java
    - backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java
    - backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java
    - backend/src/test/java/de/moviearchive/security/JwtServiceTest.java
    - backend/src/test/java/de/moviearchive/mail/MailServiceTest.java
    - backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java
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
    - (none — application-test.properties verified unchanged)
decisions:
  - "Wave 0 stub pattern confirmed: @Disabled (BE) and test.skip (FE) allow plans 02/03 to verify against real file paths without any production code"
metrics:
  duration: ~10 minutes
  completed: 2026-05-15T17:22:06Z
  tasks_completed: 2
  tasks_total: 2
  files_created: 15
  files_modified: 0
---

# Phase 01 Plan 01: Backend + Frontend Test Stub Scaffolding Summary

**One-liner:** 15 disabled test stub files (6 BE + 9 FE) that compile and run cleanly, forming the Wave 0 gate required before any auth production code is written.

## What Was Built

### Backend Test Stubs (6 files)

All files extend the correct base class where appropriate and use `@Disabled("Wave 0 stub — implement in Plan 02")` on every test method.

| File | Base Class | Test Methods | Covers |
|------|-----------|--------------|--------|
| `AuthControllerTest.java` | `AbstractIntegrationTest` | 5 | AUTH controller edge cases, rate limiting |
| `AuthIntegrationTest.java` | `AbstractIntegrationTest` | 6 | Full auth flows: register, verify, login, refresh, logout, reset |
| `AuthServiceTest.java` | — (MockitoExtension) | 3 | Token expiry + consumed token validation |
| `JwtServiceTest.java` | — (plain unit) | 4 | JWT generate, validate, invalid signature, expired |
| `MailServiceTest.java` | `AbstractIntegrationTest` | 2 | Verification email, password reset email |
| `RefreshTokenServiceTest.java` | — (MockitoExtension) | 2 | Grace period race condition |

### Frontend Test Stubs (9 files)

All files use `test.skip(...)` so Vitest counts them as skipped, not failed.

| File | Test Methods | Covers |
|------|-------------|--------|
| `stores/auth.spec.ts` | 5 | Pinia auth store: setAuth, clearAuth, isAuthenticated, refresh, no localStorage |
| `composables/useAuth.spec.ts` | 6 | login, signup, logout, verifyEmail, forgotPassword, resetPassword |
| `middleware/auth.spec.ts` | 8 | Global route guard: protected routes, 6 public routes |
| `plugins/auth.spec.ts` | 3 | Client plugin refresh-on-init: success, failure, no-op |
| `pages/login.spec.ts` | 7 | Login page: render, 401, 403, 429, success redirect, store update, spinner |
| `pages/signup.spec.ts` | 6 | Signup page: render, validation, redirect, 409, no auto-login |
| `pages/forgot-password.spec.ts` | 4 | Forgot-password: render, 200 (any email), 429, button disabled |
| `pages/reset-password.spec.ts` | 5 | Reset-password: fields, mismatch, short password, success, expired |
| `pages/verify-email.spec.ts` | 4 | Verify-email: loading, success, expired 400, consumed 400 |

### application-test.properties (verified, no changes)

- `spring.mail.port=3025` — GreenMail SMTP port confirmed
- `jwt.secret=test-secret-key-that-is-long-enough-32c` — 36 chars, >= 32 required

## Verification Results

### Backend: `./gradlew compileTestJava`

```
BUILD SUCCESSFUL
```

Exit code: 0. All 6 stub files compile without errors.

### Frontend: `pnpm test --run`

```
Test Files  2 passed | 9 skipped (11)
      Tests  6 passed | 48 skipped (54)
   Duration  ~1.74s
```

Exit code: 0. All 48 new stubs are skipped. Existing 6 tests still pass.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | `003f1fc` | `feat(01-01): create backend test stub files (Wave 0 gate)` |
| Task 2 | `981c8c4` | `feat(01-01): create frontend test stub files (Wave 0 gate)` |

## Deviations from Plan

None — plan executed exactly as written.

- `application-test.properties` was already correct and left unchanged (confirmed per plan instruction).
- `pnpm install` was needed in the worktree since `node_modules` was absent — this is a worktree setup artifact, not a deviation.

## Known Stubs

All 15 test files are intentional stubs. They contain no production-logic data flow. This is by design: Plan 01 is the Wave 0 gate, and all stubs will be implemented in Plans 02 and 03.

| Stub Pattern | Files | Reason |
|-------------|-------|--------|
| `@Disabled` methods | All 6 BE files | Placeholder — implementation in Plan 02 |
| `test.skip` methods | All 9 FE files | Placeholder — implementation in Plan 03 |

## Self-Check: PASSED

Files verified present:
- `backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java` — FOUND
- `backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java` — FOUND
- `backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java` — FOUND
- `backend/src/test/java/de/moviearchive/security/JwtServiceTest.java` — FOUND
- `backend/src/test/java/de/moviearchive/mail/MailServiceTest.java` — FOUND
- `backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java` — FOUND
- `frontend/test/unit/stores/auth.spec.ts` — FOUND
- `frontend/test/unit/composables/useAuth.spec.ts` — FOUND
- `frontend/test/unit/middleware/auth.spec.ts` — FOUND
- `frontend/test/unit/plugins/auth.spec.ts` — FOUND
- `frontend/test/unit/pages/login.spec.ts` — FOUND
- `frontend/test/unit/pages/signup.spec.ts` — FOUND
- `frontend/test/unit/pages/forgot-password.spec.ts` — FOUND
- `frontend/test/unit/pages/reset-password.spec.ts` — FOUND
- `frontend/test/unit/pages/verify-email.spec.ts` — FOUND

Commits verified:
- `003f1fc` — FOUND
- `981c8c4` — FOUND
