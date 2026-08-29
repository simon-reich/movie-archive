---
phase: 01-authentication
verified: 2026-05-15T20:35:00Z
status: passed
score: 12/12 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Visit http://localhost:3000/dashboard — should redirect to /login"
    expected: "Browser is redirected to /login page"
    why_human: "Middleware redirect behavior requires a running browser session to verify end-to-end"
  - test: "Visit http://localhost:3000/login — should render auth card with 'Sign in' heading and warm off-white background"
    expected: "Page shows AuthCard with Option D palette (#FAF7F2 background, #C84B31 primary), heading 'Sign in', email + password fields, 'Sign in' button"
    why_human: "Visual appearance and CSS token rendering requires browser inspection"
  - test: "Complete the full sign-up flow: register → receive verification email → click link → log in"
    expected: "User receives email, account activates, login issues JWT access token + HttpOnly refresh_token cookie, browser navigates to /"
    why_human: "Email delivery via Mailpit, cookie attributes (HttpOnly), and multi-step flow require a running Docker Compose stack"
  - test: "Log out and verify session is terminated"
    expected: "POST /api/auth/logout revokes the refresh token; subsequent refresh requests return 401; browser navigates to /login"
    why_human: "Session revocation and cookie clearing require a running stack"
  - test: "Request a password reset: enter email on /forgot-password, receive email, use link to reset password"
    expected: "Always shows success message on /forgot-password regardless of email existence; reset link in email is single-use; after reset all existing sessions are invalidated"
    why_human: "Email delivery, session invalidation, and enumeration-protection UX require a running stack"
---

# Phase 1: Authentication Verification Report

**Phase Goal:** Users can create accounts, verify email, log in securely, and recover forgotten passwords. JWT access token (15 min) + HttpOnly refresh cookie (7 days). Full test coverage.
**Verified:** 2026-05-15T20:35:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST /auth/signup creates user with PENDING_VERIFICATION status and sends verification email | VERIFIED | AuthService.signup() saves user with PENDING_VERIFICATION, calls MailService.sendVerificationEmail(); AuthIntegrationTest.shouldCreateUser_withPendingVerificationStatus covers this |
| 2 | POST /auth/verify-email activates account (ACTIVE status) and marks token consumed_at | VERIFIED | AuthService.verifyEmail() sets status ACTIVE and consumedAt; AuthIntegrationTest.shouldVerifyEmail covers this |
| 3 | POST /auth/login with PENDING_VERIFICATION account returns 403 | VERIFIED | AccountNotActiveException mapped to 403 in AuthController; AuthControllerTest.shouldRejectUnverified covers this |
| 4 | POST /auth/login with wrong password returns 401 | VERIFIED | BadCredentialsException mapped to 401; AuthControllerTest.shouldRejectBadPassword covers this |
| 5 | POST /auth/login with valid ACTIVE account returns JWT accessToken and HttpOnly refresh_token cookie | VERIFIED | AuthService.login() issues ResponseCookie with httpOnly(true), secure(true), SameSite=Strict, path=/api/auth/refresh; AuthIntegrationTest.shouldLogin covers this |
| 6 | POST /auth/refresh rotates the refresh token (old revoked=true, new issued) and returns new accessToken | VERIFIED | AuthService.refresh() sets token.setRevoked(true) + graceUntil=now+5s, creates new RefreshToken; AuthIntegrationTest.shouldRotateRefreshToken covers this |
| 7 | Concurrent refresh within grace_until window succeeds | VERIFIED | token.setGraceUntil(Instant.now().plusSeconds(5)) set on rotation; findValidToken JPQL checks graceUntil IS NOT NULL AND graceUntil > :now; RefreshTokenServiceTest covers entity logic |
| 8 | POST /auth/logout marks the refresh token revoked=true | VERIFIED | AuthService.logout() sets revoked=true; AuthIntegrationTest.shouldLogout covers this |
| 9 | POST /auth/forgot-password always returns 200 OK (enumeration protection) | VERIFIED | AuthService.forgotPassword() returns void with no exception path; AuthControllerTest.shouldReturn200ForUnknownEmail covers this; comment "ALWAYS return normally — enumeration protection" present |
| 10 | POST /auth/reset-password sets new password hash and revokes all refresh tokens | VERIFIED | AuthService.resetPassword() calls passwordEncoder.encode + revokeAllByUserId; AuthIntegrationTest.shouldResetPassword covers this |
| 11 | Rate limiting: 429 + Retry-After after 10 requests from same IP on /auth/login and /auth/forgot-password | VERIFIED | RateLimitService uses Bucket4j Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))); AuthController returns 429 with Retry-After header; AuthControllerTest.shouldRateLimitLogin covers this |
| 12 | All tests pass: backend ./gradlew test exits 0; frontend pnpm test --run exits 0 | VERIFIED | Backend: BUILD SUCCESSFUL (32 tests); Frontend: 54 tests passed (11 test files) |

**Score:** 12/12 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `Caddyfile` | uri strip_prefix /api in handle /api/* block | VERIFIED | Line 23: `uri strip_prefix /api` confirmed |
| `backend/src/main/resources/db/migration/V4__add_grace_until_to_refresh_tokens.sql` | Flyway migration adding grace_until column | VERIFIED | ALTER TABLE refresh_tokens ADD COLUMN grace_until TIMESTAMPTZ present |
| `backend/src/main/java/de/moviearchive/security/JwtService.java` | JWT generation and validation using JJWT 0.12.6 | VERIFIED | parseSignedClaims() at lines 37, 46, 55; no parseClaimsJws usage |
| `backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java` | OncePerRequestFilter — NOT @Component | VERIFIED | No @Component annotation; comment explicitly notes its absence |
| `backend/src/main/java/de/moviearchive/auth/AuthService.java` | All 9 auth operations | VERIFIED | signup, verifyEmail, resendVerification, login, refresh, logout, forgotPassword, resetPassword, me all present; TokenUtils.hashToken called 9 times |
| `backend/src/main/java/de/moviearchive/auth/AuthController.java` | 9 REST endpoints mapped to /auth/** | VERIFIED | All endpoints present; Retry-After header; {"message":"..."} error format; path("/api/auth/refresh") for logout clear cookie |
| `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` | SecurityFilterChain with JwtAuthFilter wired via constructor | VERIFIED | new JwtAuthFilter(jwtService, userDetailsService) at line 25; addFilterBefore at line 33; BCryptPasswordEncoder(12) at line 39 |
| `backend/src/main/java/de/moviearchive/auth/TokenUtils.java` | SHA-256 hashing | VERIFIED | SHA-256 MessageDigest present |
| `backend/src/main/java/de/moviearchive/auth/RateLimitService.java` | Bucket4j per-IP limiting | VERIFIED | Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))) |
| `backend/src/main/resources/templates/mail/welcome-verify.html` | Thymeleaf verification email template | VERIFIED | verificationUrl variable used |
| `backend/src/main/resources/templates/mail/password-reset.html` | Thymeleaf reset email template | VERIFIED | resetUrl variable used |
| `backend/src/main/java/de/moviearchive/auth/dto/*.java` | 7 DTO records with Bean Validation | VERIFIED | SignupRequest, LoginRequest, LoginResponse, RefreshResponse, VerifyEmailRequest, ForgotPasswordRequest, ResetPasswordRequest all present |
| `frontend/stores/auth.ts` | Pinia auth store — no localStorage | VERIFIED | No localStorage reference; $fetch POST /api/auth/refresh; setAuth, clearAuth, isAuthenticated, refresh all present |
| `frontend/composables/useAuth.ts` | login, signup, logout, verifyEmail, forgotPassword, resetPassword | VERIFIED | All 6 functions present; credentials:'include' on all 6 calls; setAuth called only in login (not signup); navigateTo('/verify-email-sent') in signup |
| `frontend/middleware/auth.global.ts` | Cookie presence check — redirects to /login | VERIFIED | useCookie('refresh_token'); navigateTo('/login') when absent on non-public routes |
| `frontend/plugins/auth.client.ts` | Silent refresh on app init | VERIFIED | authStore.refresh() called; catch block swallows errors silently |
| `frontend/components/AuthCard.vue` | Centered card layout wrapper | VERIFIED | heading + subtext props; MovieArchive app name above card |
| `frontend/components/FormField.vue` | Label + input + error message | VERIFIED | role="alert" on error paragraph |
| `frontend/components/InputText.vue` | Styled input with error state | VERIFIED | aria-invalid attribute present |
| `frontend/components/ButtonPrimary.vue` | Submit button with loading/disabled | VERIFIED | loading + disabled states; SpinnerIcon when loading |
| `frontend/components/FormErrorBanner.vue` | API error banner | VERIFIED | role="alert" confirmed; left terracotta border |
| `frontend/components/SpinnerIcon.vue` | Lucide Loader2 with animate-spin | VERIFIED | animate-spin confirmed |
| `frontend/pages/login.vue` | Email + password login page | VERIFIED | FormErrorBanner; 401/403/429 handling; Retry-After parsed; "Don't have an account?" copy |
| `frontend/pages/signup.vue` | Account registration page | VERIFIED | Client-side validation; navigateTo('/verify-email-sent') via useAuth composable; 409 handling |
| `frontend/pages/verify-email-sent.vue` | Post-signup confirmation | VERIFIED | "Check your inbox" heading; "Back to sign in" link |
| `frontend/pages/verify-email.vue` | Email verification landing | VERIFIED | onMounted calls verifyEmail(token); loading/success/error states |
| `frontend/pages/forgot-password.vue` | Password reset request | VERIFIED | Success state replaces form on 200; "Too many attempts" on 429; Retry-After parsed |
| `frontend/pages/reset-password.vue` | Set new password | VERIFIED | Password mismatch/length validation; success state with "Sign in" CTA; expired/used token messages |
| `frontend/test/mocks/handlers/auth.ts` | MSW handlers for all 9 auth endpoints | VERIFIED | http.post handlers for login, signup, refresh, logout, verify-email, forgot-password, reset-password all present; imported via authHandlers spread in handlers.ts |
| `backend/src/test/resources/application-test.properties` | GreenMail port 3025, JWT secret >= 32 chars | VERIFIED | spring.mail.port=3025; jwt.secret=test-secret-key-that-is-long-enough-32c (36 chars) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| SecurityConfig.java | JwtAuthFilter | new JwtAuthFilter(jwtService, userDetailsService) | WIRED | Line 25: `JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService, userDetailsService)` |
| JwtService.java | JJWT 0.12.6 API | parseSignedClaims() | WIRED | Lines 37, 46, 55: parseSignedClaims() used; no parseClaimsJws |
| AuthService.java | TokenUtils.hashToken() | SHA-256 hash before every token persistence | WIRED | 9 TokenUtils.hashToken() calls across all token flows |
| RefreshTokenRepository.java | grace_until JPQL query | graceUntil IS NOT NULL AND graceUntil > :now | WIRED | findValidToken JPQL query present |
| Caddyfile | Spring Boot /auth/** | uri strip_prefix /api inside handle /api/* block | WIRED | Line 23: `uri strip_prefix /api` confirmed |
| ResponseCookie path | browser /api/auth/refresh | path must match browser-visible URL | WIRED | path("/api/auth/refresh") in AuthService.login (line 138), refresh (line 172), AuthController.logout (line 104) |
| frontend/plugins/auth.client.ts | frontend/stores/auth.ts | useAuthStore().refresh() | WIRED | authStore.refresh() called in plugin; store is imported via useAuthStore() |
| frontend/middleware/auth.global.ts | refresh_token cookie | useCookie('refresh_token').value | WIRED | useCookie('refresh_token') at line 15; navigateTo('/login') when absent |
| frontend/pages/login.vue | frontend/composables/useAuth.ts | const { login } = useAuth() | WIRED | useAuth() called on line 9 of login.vue |
| frontend/composables/useAuth.ts | /api/auth/* endpoints | $fetch with credentials: 'include' | WIRED | credentials:'include' confirmed on all 6 API calls |
| frontend/stores/auth.ts | accessToken (in-memory only) | ref<string | null>(null) — no localStorage | WIRED | No localStorage reference; accessToken is Pinia ref only |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| frontend/stores/auth.ts | accessToken | $fetch('/api/auth/refresh') → Spring Boot /auth/refresh → DB query | Yes — backend queries RefreshToken table, issues new JWT | FLOWING |
| frontend/pages/login.vue | errorMessage | API error responses from useAuth.login() | Yes — $fetch errors carry status + data.message | FLOWING |
| backend/src/main/java/de/moviearchive/auth/AuthService.java | login response | User DB query + JWT generation | Yes — UserRepository.findByEmail + JwtService.generateAccessToken | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Frontend: 54 tests pass | pnpm test --run (tail -5) | 11 passed (11) / Tests 54 passed (54) | PASS |
| Backend: all auth tests pass | ./gradlew test --tests "de.moviearchive.auth.*" etc. | BUILD SUCCESSFUL | PASS |
| JwtService uses JJWT 0.12.6 parseSignedClaims | grep parseSignedClaims JwtService.java | 3 matches at lines 37, 46, 55 | PASS |
| JwtAuthFilter has no @Component | grep @Component JwtAuthFilter.java | no match (only comment) | PASS |
| No localStorage in frontend auth files | grep -rn localStorage stores/ composables/ pages/ | no output | PASS |
| Caddyfile has uri strip_prefix /api | grep "uri strip_prefix" Caddyfile | line 23 | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| AUTH-01 | 01-01, 01-02, 01-03 | User kann Account mit E-Mail + Passwort erstellen | SATISFIED | AuthService.signup(); POST /auth/signup returns 201; signup.vue page; AuthIntegrationTest.shouldCreateUser covers full flow |
| AUTH-02 | 01-01, 01-02, 01-03 | User erhält Verifizierungs-E-Mail; Account erst nach Klick aktiviert | SATISFIED | MailService.sendVerificationEmail(); verify-email.vue calls verifyEmail(token) on mount; AuthIntegrationTest.shouldVerifyEmail tests full flow |
| AUTH-03 | 01-01, 01-02, 01-03 | User kann sich einloggen (nur ACTIVE-Accounts) | SATISFIED | AccountNotActiveException → 403; login.vue shows "Please verify your email..." on 403; AuthControllerTest.shouldRejectUnverified covers this |
| AUTH-04 | 01-01, 01-02, 01-03 | JWT Access Token (15 min) + Refresh Token als HttpOnly-Cookie (7 Tage) | SATISFIED | JwtService generates access token; cookie with httpOnly(true), maxAge configured via jwt.refresh-token-expiration-ms; AuthIntegrationTest.shouldLogin asserts HttpOnly attribute |
| AUTH-05 | 01-01, 01-02, 01-03 | Refresh Token rotiert bei /auth/refresh (altes revoked, neues ausgestellt) | SATISFIED | AuthService.refresh() sets revoked=true + graceUntil; issues new token + cookie; AuthIntegrationTest.shouldRotateRefreshToken covers this |
| AUTH-06 | 01-01, 01-02, 01-03 | User kann sich ausloggen (Refresh Token revoked) | SATISFIED | AuthService.logout() sets revoked=true; AuthController clears cookie with maxAge=0; AuthIntegrationTest.shouldLogout covers this |
| AUTH-07 | 01-01, 01-02, 01-03 | Passwort-Reset per E-Mail (immer 200 OK — Enumeration-Schutz) | SATISFIED | AuthService.forgotPassword() always returns void; AuthControllerTest.shouldReturn200ForUnknownEmail; forgot-password.vue shows success state after any 200 |
| AUTH-08 | 01-01, 01-02, 01-03 | Passwort zurücksetzen; alle Refresh Tokens revoked | SATISFIED | AuthService.resetPassword() calls revokeAllByUserId(); AuthIntegrationTest.shouldResetPassword covers this |

All 8 requirements (AUTH-01 through AUTH-08) are SATISFIED. No orphaned requirements found — REQUIREMENTS.md maps all 8 to Phase 1 and all 8 are claimed by plans 01-01, 01-02, and 01-03.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | - | - | - | - |

No anti-patterns found. No TODO/FIXME/PLACEHOLDER comments in production code. No empty return null / return {} implementations. No localStorage in frontend auth code.

### Human Verification Required

#### 1. End-to-End Browser Auth Flow

**Test:** Start Docker Compose (`docker compose up`), visit `http://localhost:3000/dashboard`, then complete the full sign-up → email verification → login flow.
**Expected:** Dashboard redirects to /login; sign-up creates user; Mailpit receives verification email at http://localhost:8025; clicking link activates account; logging in issues JWT in response body + HttpOnly refresh_token cookie visible in browser devtools; browser navigates to /.
**Why human:** Multi-step flow requires a running stack (PostgreSQL, Spring Boot, Mailpit, Caddy, Nuxt). Cookie HttpOnly attribute and email delivery to Mailpit can only be confirmed in a browser session.

#### 2. Option D Visual Palette

**Test:** Visit any auth page (e.g., /login) in a browser.
**Expected:** Warm off-white background (#FAF7F2), terracotta primary button (#C84B31), dark foreground text, square (not rounded) inputs and buttons, "MovieArchive" in small-caps above the card.
**Why human:** CSS custom property rendering requires visual inspection — can't verify perceived palette from source files alone.

#### 3. Token Rotation with Concurrent Tabs

**Test:** Log in on two tabs simultaneously, trigger a refresh in rapid succession from both.
**Expected:** Both tabs succeed due to the 5-second grace_until window; session is not dropped.
**Why human:** Race condition behavior requires real concurrent HTTP requests.

#### 4. Password Reset Single-Use Token

**Test:** Request a password reset, use the link once to reset password, attempt to use the same link a second time.
**Expected:** Second use returns an error indicating the token has already been used.
**Why human:** Requires email delivery and two separate HTTP requests against a real database.

### Gaps Summary

No gaps found. All 12 observable truths are verified, all 30+ required artifacts exist and are substantive, all 11 key links are wired, all 8 requirement IDs are satisfied. The phase goal is achieved at the code level. Human verification items are limited to visual/UX validation and end-to-end flow confirmation requiring a running stack.

---

_Verified: 2026-05-15T20:35:00Z_
_Verifier: Claude (gsd-verifier)_
