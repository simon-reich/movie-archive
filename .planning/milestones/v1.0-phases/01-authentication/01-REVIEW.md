---
phase: 01-authentication
reviewed: 2026-05-15T00:00:00Z
depth: standard
files_reviewed: 57
files_reviewed_list:
  - Caddyfile
  - backend/src/main/java/de/moviearchive/auth/AccountNotActiveException.java
  - backend/src/main/java/de/moviearchive/auth/AuthController.java
  - backend/src/main/java/de/moviearchive/auth/AuthService.java
  - backend/src/main/java/de/moviearchive/auth/EmailAlreadyExistsException.java
  - backend/src/main/java/de/moviearchive/auth/RateLimitService.java
  - backend/src/main/java/de/moviearchive/auth/TokenAlreadyConsumedException.java
  - backend/src/main/java/de/moviearchive/auth/TokenExpiredException.java
  - backend/src/main/java/de/moviearchive/auth/TokenNotFoundException.java
  - backend/src/main/java/de/moviearchive/auth/TokenUtils.java
  - backend/src/main/java/de/moviearchive/auth/dto/ForgotPasswordRequest.java
  - backend/src/main/java/de/moviearchive/auth/dto/LoginRequest.java
  - backend/src/main/java/de/moviearchive/auth/dto/LoginResponse.java
  - backend/src/main/java/de/moviearchive/auth/dto/RefreshResponse.java
  - backend/src/main/java/de/moviearchive/auth/dto/ResetPasswordRequest.java
  - backend/src/main/java/de/moviearchive/auth/dto/SignupRequest.java
  - backend/src/main/java/de/moviearchive/auth/dto/VerifyEmailRequest.java
  - backend/src/main/java/de/moviearchive/config/SecurityConfig.java
  - backend/src/main/java/de/moviearchive/mail/MailService.java
  - backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java
  - backend/src/main/java/de/moviearchive/security/JwtService.java
  - backend/src/main/java/de/moviearchive/security/UserDetailsServiceImpl.java
  - backend/src/main/java/de/moviearchive/token/RefreshToken.java
  - backend/src/main/java/de/moviearchive/token/RefreshTokenRepository.java
  - backend/src/main/resources/db/migration/V4__add_grace_until_to_refresh_tokens.sql
  - backend/src/main/resources/templates/mail/password-reset.html
  - backend/src/main/resources/templates/mail/welcome-verify.html
  - backend/src/test/java/de/moviearchive/AbstractIntegrationTest.java
  - backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java
  - backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java
  - backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java
  - backend/src/test/java/de/moviearchive/mail/MailServiceTest.java
  - backend/src/test/java/de/moviearchive/security/JwtServiceTest.java
  - backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java
  - frontend/assets/css/main.css
  - frontend/components/AuthCard.vue
  - frontend/components/ButtonPrimary.vue
  - frontend/components/FormErrorBanner.vue
  - frontend/components/FormField.vue
  - frontend/components/InputText.vue
  - frontend/components/SpinnerIcon.vue
  - frontend/composables/useAuth.ts
  - frontend/middleware/auth.global.ts
  - frontend/pages/forgot-password.vue
  - frontend/pages/login.vue
  - frontend/pages/reset-password.vue
  - frontend/pages/signup.vue
  - frontend/pages/verify-email-sent.vue
  - frontend/pages/verify-email.vue
  - frontend/plugins/auth.client.ts
  - frontend/stores/auth.ts
  - frontend/test/mocks/handlers.ts
  - frontend/test/mocks/handlers/auth.ts
  - frontend/test/unit/composables/useAuth.spec.ts
  - frontend/test/unit/middleware/auth.spec.ts
  - frontend/test/unit/pages/forgot-password.spec.ts
  - frontend/test/unit/pages/login.spec.ts
  - frontend/test/unit/pages/reset-password.spec.ts
  - frontend/test/unit/pages/signup.spec.ts
  - frontend/test/unit/pages/verify-email.spec.ts
  - frontend/test/unit/plugins/auth.spec.ts
  - frontend/test/unit/stores/auth.spec.ts
findings:
  critical: 1
  warning: 5
  info: 3
  total: 9
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-05-15T00:00:00Z
**Depth:** standard
**Files Reviewed:** 57
**Status:** issues_found

## Summary

The authentication phase covers signup, email verification, login (JWT + refresh-token cookie), password reset, logout, and the corresponding Nuxt frontend pages. The overall design is sound: tokens are stored as SHA-256 hashes, BCrypt strength is 12, the refresh-token rotation with a 5-second grace window is well-reasoned, and enumeration protection is consistently applied on forgot-password and resend-verification paths.

One critical security issue was found: the `X-Forwarded-For` header used for rate limiting is read directly from the incoming request without any validation or allowlisting, making it trivially bypassable by a client that fabricates the header. Five warnings cover correctness risks: a full-table-scan in `resendVerification`, a login flow that leaks user existence via an early status check before the password check, incorrect order of expired/consumed checks in `refresh`, the missing `Content-Security-Policy` header in the Caddyfile, and the in-memory rate-limit bucket map having no eviction mechanism. Three info items cover minor quality gaps.

---

## Critical Issues

### CR-01: Rate-limit IP spoofing — `X-Forwarded-For` not validated against trusted proxy

**File:** `backend/src/main/java/de/moviearchive/auth/AuthController.java:181-185`

**Issue:** `resolveClientIp` unconditionally trusts the first value of the `X-Forwarded-For` header. Because this header can be set by any HTTP client, an attacker can cycle through arbitrary values (e.g., `X-Forwarded-For: 1.2.3.4`, `1.2.3.5`, …) to bypass the login and forgot-password rate limiter entirely. Bucket4j protects the fabricated IP, not the real client.

**Fix:** Only trust `X-Forwarded-For` when the connection originates from a known trusted proxy. In this stack, the proxy is Caddy running on the Docker internal network. The simplest server-side defence is to ignore the header and use `request.getRemoteAddr()` exclusively when the remote address is not a trusted proxy CIDR, or to configure Caddy to set a single authoritative header (e.g., `X-Real-IP`) and strip any client-supplied copy:

```caddy
# Caddyfile — strip attacker-supplied header before forwarding
handle /api/* {
    request_header -X-Forwarded-For
    uri strip_prefix /api
    reverse_proxy backend:8080 {
        header_up X-Forwarded-For {remote_host}
        header_up X-Real-IP {remote_host}
    }
}
```

Then in `AuthController`, read only `X-Real-IP` (which Caddy always sets from the actual remote host) and fall back to `request.getRemoteAddr()`:

```java
private String resolveClientIp(HttpServletRequest request) {
    String realIp = request.getHeader("X-Real-IP");
    return (realIp != null && !realIp.isBlank()) ? realIp.trim() : request.getRemoteAddr();
}
```

---

## Warnings

### WR-01: Login leaks user existence — status check precedes password check

**File:** `backend/src/main/java/de/moviearchive/auth/AuthService.java:117-123`

**Issue:** `login` checks `user.getStatus() != ACTIVE` and throws `AccountNotActiveException` (403) before it verifies the password. An attacker can distinguish between (a) unknown email → 401, (b) known email + wrong password → 401, and (c) known email + any password + unverified account → 403. Case (c) confirms that the email exists and is registered but unverified, enabling email enumeration via the login endpoint — even though the rest of the flow (forgot-password, resend-verification) is correctly protected.

**Fix:** Perform the password check first, throw `BadCredentialsException` on mismatch, and only then check the account status:

```java
public LoginResponse login(LoginRequest req, HttpServletResponse response) {
    User user = userRepository.findByEmail(req.email())
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
    // Verify password before revealing account state
    if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
        throw new BadCredentialsException("Invalid email or password");
    }
    if (user.getStatus() != UserStatus.ACTIVE) {
        throw new AccountNotActiveException();
    }
    // ... issue tokens
}
```

---

### WR-02: `resendVerification` performs a full table scan on `email_verification_tokens`

**File:** `backend/src/main/java/de/moviearchive/auth/AuthService.java:105-107`

**Issue:** The code calls `emailVerificationTokenRepository.findAll()` and filters in Java. This loads every row in the table into memory on every resend request. While harmless with a single user, this will cause correctness issues under load and is architecturally wrong.

**Fix:** Add a repository method that queries by user ID directly:

```java
// EmailVerificationTokenRepository
@Modifying
@Query("DELETE FROM EmailVerificationToken t WHERE t.user.id = :userId AND t.consumedAt IS NULL")
void deleteUnconsumedByUserId(@Param("userId") UUID userId);
```

Then replace the `findAll().stream().filter().forEach(delete)` block with a single call:

```java
emailVerificationTokenRepository.deleteUnconsumedByUserId(user.getId());
```

---

### WR-03: `refresh` checks `token.isExpired()` after `findValidToken` already filters expired tokens

**File:** `backend/src/main/java/de/moviearchive/auth/AuthService.java:147-153`

**Issue:** `findValidToken` uses a JPQL query that returns a token only when `revoked = false OR graceUntil > now`. It does **not** filter on `expiresAt`. The subsequent `token.isExpired()` check is the only guard against an expired (but not revoked) token slipping through. The ordering is correct in the sense that it does catch expiry, but the secondary `isExpired()` branch on lines 149-153 sets `revoked = true` and saves the token, then throws — meaning an expired token that is returned by `findValidToken` will be marked revoked via a database write before the exception is thrown. If the caller (or a concurrent request in the grace window) retries within the same grace window, the now-revoked token would not be found by `findValidToken`, which is correct, but the explicit save is redundant since the token is about to be abandoned. More importantly, `findValidToken` should also filter `expiresAt > now` to avoid the dead code path entirely and make the invariant explicit. This is a logic correctness issue — expiry is currently silently "handled" by dead-code cleanup rather than by the query contract.

**Fix:** Update `findValidToken` to also reject expired tokens at the database level:

```java
@Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :hash " +
       "AND rt.expiresAt > :now " +
       "AND (rt.revoked = false OR (rt.graceUntil IS NOT NULL AND rt.graceUntil > :now))")
Optional<RefreshToken> findValidToken(@Param("hash") String hash, @Param("now") Instant now);
```

Then the `token.isExpired()` branch in `AuthService.refresh` becomes unreachable and can be removed, making the flow easier to reason about.

---

### WR-04: Missing `Content-Security-Policy` header in Caddyfile

**File:** `Caddyfile:52-58`

**Issue:** The security header block sets `X-Content-Type-Options`, `X-Frame-Options`, and `Referrer-Policy` but omits `Content-Security-Policy`. Without a CSP, any XSS vulnerability in the Nuxt frontend can load arbitrary scripts from external origins. This matters particularly because the app will later handle user authentication state (access tokens in memory, reset token flows in URLs).

**Fix:** Add a restrictive CSP to the Caddyfile header block. A starting point for the auth pages (no inline scripts needed in production):

```caddy
header {
    X-Content-Type-Options nosniff
    X-Frame-Options DENY
    Referrer-Policy strict-origin-when-cross-origin
    Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
    -Server
}
```

Adjust `style-src` as needed when Tailwind is built into a bundle. The `'unsafe-inline'` for styles is acceptable because styles cannot exfiltrate tokens; scripts cannot be inlined.

---

### WR-05: In-memory rate-limit bucket map grows without bound

**File:** `backend/src/main/java/de/moviearchive/auth/RateLimitService.java:15-20`

**Issue:** `buckets` is a `ConcurrentHashMap` that adds an entry per unique IP and never removes entries. Every IP that ever touches `/auth/login` or `/auth/forgot-password` creates a permanent entry. In a container that runs for days with normal internet traffic (bots, scanners), this is a slow memory leak.

**Fix:** Replace the `ConcurrentHashMap` with a time-based evicting cache, e.g. Caffeine (already available via Spring Boot's caching auto-configuration):

```java
private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .maximumSize(100_000)
        .build();

private Bucket resolveBucket(String ip) {
    return buckets.get(ip, k ->
            Bucket.builder()
                    .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
                    .build());
}
```

---

## Info

### IN-01: `AuthService` class is annotated `@Transactional` at class level but `resendVerification` performs multiple deletes + insert that should be atomic

**File:** `backend/src/main/java/de/moviearchive/auth/AuthService.java:36`

**Issue:** The class-level `@Transactional` ensures all public methods run in a transaction, which is correct. However, the `resendVerification` method first calls `emailVerificationTokenRepository.findAll()` (which cannot be done atomically with the subsequent deletes using the current approach), so even with a transaction, a race between two concurrent resend requests could create duplicate tokens. This is a latent correctness issue that becomes active once WR-02 is fixed with a proper bulk-delete query.

**Fix:** After applying WR-02's `deleteUnconsumedByUserId`, the full method runs atomically inside the inherited class-level transaction. No additional annotation is needed — just ensure the repository method is `@Modifying` (as shown in WR-02). Document the atomicity expectation with a comment.

---

### IN-02: `AuthControllerTest` rate-limit test uses `Math.random()` for IP generation — flaky under parallel test execution

**File:** `backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java:102`

**Issue:** The test uses `Math.random()` to generate a unique IP to avoid bucket state from other tests. This has a 1-in-65025 collision probability per run and is non-deterministic. When parallelism increases, this can produce spurious 429 results from bucket sharing.

**Fix:** Use a fixed, test-class-unique IP that cannot collide with other test classes:

```java
// Stable unique IP for this test class, outside the 10.0.x.x range used in other tests
private static final String RATE_LIMIT_TEST_IP = "192.168.99.99";
```

Or generate the IP from `UUID.randomUUID()` trimmed to four octets deterministically.

---

### IN-03: Frontend middleware test duplicates the public-routes list instead of importing from the middleware

**File:** `frontend/test/unit/middleware/auth.spec.ts:6-9`

**Issue:** The test file re-declares `publicRoutes` as a local constant and tests a local `simulateMiddleware` function rather than importing and exercising the actual middleware. If a route is added to or removed from `middleware/auth.global.ts`, the test will not catch the regression.

**Fix:** Extract the `publicRoutes` array from `frontend/middleware/auth.global.ts` into a shared constant (e.g., `frontend/constants/publicRoutes.ts`) and import it in both the middleware and the test. The test should then import that same constant to assert coverage.

---

_Reviewed: 2026-05-15T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
