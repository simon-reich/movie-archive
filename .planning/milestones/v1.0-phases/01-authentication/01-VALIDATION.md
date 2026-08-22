---
phase: 1
slug: authentication
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-15
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (BE)** | JUnit 5 + Mockito + Testcontainers + GreenMail + MockMvc |
| **Framework (FE)** | Vitest 3.1.3 + Vue Test Utils 2.4.6 + MSW 2.7.5 |
| **Config file (BE)** | `backend/build.gradle.kts` (useJUnitPlatform()) |
| **Config file (FE)** | `frontend/vitest.config.ts` |
| **Quick run command (BE)** | `./gradlew test --tests "de.moviearchive.auth.*"` |
| **Full suite command (BE)** | `./gradlew test jacocoTestReport` |
| **Quick run command (FE)** | `pnpm test --run` |
| **Full suite command (FE)** | `pnpm test --run --coverage` |
| **Estimated runtime (BE quick)** | ~30 seconds |
| **Estimated runtime (BE full)** | ~90 seconds |

---

## Sampling Rate

- **After every task commit (BE):** Run `./gradlew test --tests "de.moviearchive.auth.*"`
- **After every task commit (FE):** Run `pnpm test --run test/unit/`
- **After every plan wave:** Run `./gradlew test jacocoTestReport` + `pnpm test --run --coverage`
- **Before `/gsd-verify-work`:** Full suite must be green; coverage ≥ 75% overall, ≥ 85% service layer
- **Max feedback latency:** ~30 seconds (BE quick), ~10 seconds (FE quick)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 1-01-01 | 01 | 0 | AUTH-01..08 | T-1-04 | GreenMail receives mail on port 3025 only | Setup | `./gradlew test --tests "*MailServiceTest*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-01 | 02 | 1 | AUTH-01 | T-1-01 | Duplicate email → 409; raw token never stored | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldCreateUser*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-02 | 02 | 1 | AUTH-02 | T-1-03 | Token consumed_at set; status → ACTIVE | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldVerifyEmail*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-03 | 02 | 1 | AUTH-03 | T-1-01 | PENDING_VERIFICATION → 403; bad password → 401 | Controller | `./gradlew test --tests "*AuthControllerTest.shouldRejectUnverified*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-04 | 02 | 1 | AUTH-04/05 | T-1-02 | Old token revoked after rotation; cookie HttpOnly | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldRotateRefreshToken*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-05 | 02 | 1 | AUTH-05 | T-1-02 | Concurrent refresh within grace_until succeeds | Unit | `./gradlew test --tests "*RefreshTokenServiceTest.shouldHandleGracePeriod*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-06 | 02 | 1 | AUTH-06 | T-1-02 | Logout sets revoked=true; subsequent refresh → 401 | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldLogout*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-07 | 02 | 1 | AUTH-07 | T-1-05 | Unknown email → 200 (enumeration protection) | Controller | `./gradlew test --tests "*AuthControllerTest.shouldReturn200ForUnknownEmail*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-08 | 02 | 1 | AUTH-08 | T-1-03 | Reset revokes all refresh tokens; expired token → 400 | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldResetPassword*"` | ❌ Wave 0 | ⬜ pending |
| 1-02-09 | 02 | 1 | D-06 | T-1-06 | 11th request → 429 + Retry-After header | Controller | `./gradlew test --tests "*AuthControllerTest.shouldRateLimitLogin*"` | ❌ Wave 0 | ⬜ pending |
| 1-03-01 | 03 | 2 | AUTH-01/03/04 | — | Login → store populated; no localStorage writes | FE Integration | `pnpm test --run test/unit/pages/login.spec.ts` | ❌ Wave 0 | ⬜ pending |
| 1-03-02 | 03 | 2 | D-01/02/03 | — | No cookie → redirect to /login | FE Unit | `pnpm test --run test/unit/middleware/auth.spec.ts` | ❌ Wave 0 | ⬜ pending |
| 1-03-03 | 03 | 2 | D-05 | — | Plugin calls /auth/refresh on init | FE Unit | `pnpm test --run test/unit/plugins/auth.spec.ts` | ❌ Wave 0 | ⬜ pending |
| 1-03-04 | 03 | 2 | AUTH-01 | — | Signup redirects to /verify-email-sent; no auto-login | FE Integration | `pnpm test --run test/unit/pages/signup.spec.ts` | ❌ Wave 0 | ⬜ pending |
| 1-03-05 | 03 | 2 | AUTH-07/08 | — | Forgot/reset password pages show inline errors | FE Integration | `pnpm test --run test/unit/pages/forgot-password.spec.ts test/unit/pages/reset-password.spec.ts` | ❌ Wave 0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java` — stub file for AUTH-01/03/06/07, D-06
- [ ] `backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java` — stub for AUTH-01/02/04/05/06/08 (Testcontainers + GreenMail)
- [ ] `backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java` — stub for AUTH-02/08 (Mockito unit)
- [ ] `backend/src/test/java/de/moviearchive/security/JwtServiceTest.java` — JWT creation + validation unit stubs
- [ ] `backend/src/test/java/de/moviearchive/mail/MailServiceTest.java` — AUTH-02/07 mail stubs (GreenMail)
- [ ] `backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java` — AUTH-05 grace period stubs
- [ ] `backend/src/test/resources/application-test.properties` — GreenMail port 3025, test JWT secret (≥32 chars)
- [ ] `frontend/test/unit/stores/auth.spec.ts` — Pinia store unit stubs
- [ ] `frontend/test/unit/composables/useAuth.spec.ts` — composable stubs
- [ ] `frontend/test/unit/middleware/auth.spec.ts` — middleware redirect stubs
- [ ] `frontend/test/unit/plugins/auth.spec.ts` — plugin refresh-on-init stubs
- [ ] `frontend/test/unit/pages/login.spec.ts` — login page integration stubs (MSW)
- [ ] `frontend/test/unit/pages/signup.spec.ts` — signup page stubs (MSW)
- [ ] `frontend/test/unit/pages/forgot-password.spec.ts` — forgot-password page stubs
- [ ] `frontend/test/unit/pages/reset-password.spec.ts` — reset-password page stubs
- [ ] `frontend/test/unit/pages/verify-email.spec.ts` — verify-email page stubs

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Refresh cookie `path=` scope works through Caddy proxy | AUTH-04/05 | Caddy path stripping behavior cannot be unit-tested | Start full Docker Compose stack; log in; open DevTools → Application → Cookies; verify `refresh_token` cookie present; call `/api/auth/refresh` and confirm 200 |
| Email links render correctly in mail client | AUTH-02/07 | Template rendering in real mail client varies | Use Mailpit in dev mode; send verification and reset emails; verify links open correct pages with correct tokens |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (BE), < 10s (FE)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
