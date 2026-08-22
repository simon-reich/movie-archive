---
phase: 01-authentication
plan: "02"
subsystem: backend-auth
tags: [auth, jwt, spring-security, testcontainers, rate-limiting, email]
dependency_graph:
  requires: ["01-01"]
  provides: ["AUTH-01", "AUTH-02", "AUTH-03", "AUTH-04", "AUTH-05", "AUTH-06", "AUTH-07", "AUTH-08"]
  affects: [frontend-auth, all-protected-endpoints]
tech_stack:
  added: [JJWT-0.12.6, Bucket4j-8.10.1, GreenMail-2.1.3, Thymeleaf-SpringTemplateEngine]
  patterns: [JWT-refresh-rotation, SHA-256-token-hashing, per-IP-rate-limiting, static-testcontainer-lifecycle]
key_files:
  created:
    - Caddyfile (modified: uri strip_prefix /api)
    - backend/src/main/resources/db/migration/V4__add_grace_until_to_refresh_tokens.sql
    - backend/src/main/java/de/moviearchive/auth/TokenUtils.java
    - backend/src/main/java/de/moviearchive/security/JwtService.java
    - backend/src/main/java/de/moviearchive/security/JwtAuthFilter.java
    - backend/src/main/java/de/moviearchive/security/UserDetailsServiceImpl.java
    - backend/src/main/java/de/moviearchive/auth/RateLimitService.java
    - backend/src/main/java/de/moviearchive/mail/MailService.java
    - backend/src/main/resources/templates/mail/welcome-verify.html
    - backend/src/main/resources/templates/mail/password-reset.html
    - backend/src/main/java/de/moviearchive/auth/dto/SignupRequest.java
    - backend/src/main/java/de/moviearchive/auth/dto/LoginRequest.java
    - backend/src/main/java/de/moviearchive/auth/dto/LoginResponse.java
    - backend/src/main/java/de/moviearchive/auth/dto/RefreshResponse.java
    - backend/src/main/java/de/moviearchive/auth/dto/VerifyEmailRequest.java
    - backend/src/main/java/de/moviearchive/auth/dto/ForgotPasswordRequest.java
    - backend/src/main/java/de/moviearchive/auth/dto/ResetPasswordRequest.java
    - backend/src/main/java/de/moviearchive/auth/AuthService.java
    - backend/src/main/java/de/moviearchive/auth/AuthController.java
    - backend/src/main/java/de/moviearchive/auth/EmailAlreadyExistsException.java
    - backend/src/main/java/de/moviearchive/auth/AccountNotActiveException.java
    - backend/src/main/java/de/moviearchive/auth/TokenNotFoundException.java
    - backend/src/main/java/de/moviearchive/auth/TokenExpiredException.java
    - backend/src/main/java/de/moviearchive/auth/TokenAlreadyConsumedException.java
  modified:
    - backend/src/main/java/de/moviearchive/token/RefreshToken.java (graceUntil field)
    - backend/src/main/java/de/moviearchive/token/RefreshTokenRepository.java (findValidToken JPQL)
    - backend/src/main/java/de/moviearchive/config/SecurityConfig.java (JwtAuthFilter wired)
    - backend/src/test/java/de/moviearchive/AbstractIntegrationTest.java (static container lifecycle)
    - backend/src/test/java/de/moviearchive/security/JwtServiceTest.java
    - backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java
    - backend/src/test/java/de/moviearchive/mail/MailServiceTest.java
    - backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java
    - backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java
    - backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java
decisions:
  - "JwtAuthFilter instantiated via new in SecurityConfig — no @Component to prevent double-registration with Spring's filter chain"
  - "RefreshToken cookie path=/api/auth/refresh (browser-visible URL) not /auth/refresh (Spring Boot path) — Caddy strips /api prefix before forwarding"
  - "Testcontainers: static PostgreSQLContainer started once per JVM in AbstractIntegrationTest static block + @DynamicPropertySource — removes @Testcontainers/@Container which stopped container between test classes"
  - "JJWT 0.12.6: parseSignedClaims() not parseClaimsJws(); .subject()/.expiration() builder methods not setSubject/setExpiration"
  - "forgotPassword always returns 200 — enumeration protection; debug log only for unknown emails"
  - "grace_until = now + 5 seconds on refresh rotation — allows concurrent tabs to succeed without opening a wide replay window"
metrics:
  duration: "~60 minutes (including context continuation)"
  completed: "2026-05-15"
  tests_total: 32
  tests_passed: 32
  jacoco_auth_service: "88%"
  jacoco_auth_package: "80%"
---

# Phase 01 Plan 02: Backend Auth Layer Summary

JWT authentication layer with BCrypt passwords, refresh token rotation, email verification, password reset, Bucket4j rate limiting, and GreenMail integration tests.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 0 | Caddyfile — uri strip_prefix /api | dc0f457 | Caddyfile |
| 1 | Flyway V4 + security infrastructure | de3c0e5 | JwtService, JwtAuthFilter, UserDetailsServiceImpl, SecurityConfig, TokenUtils, RateLimitService, RefreshToken (graceUntil), V4 migration |
| 2 | MailService + Thymeleaf templates + DTOs | 4e852be | MailService, welcome-verify.html, password-reset.html, 7 DTO records |
| 3 | AuthService + exception classes + AuthServiceTest | 541b40e | AuthService (9 ops), 5 exception classes, AuthServiceTest (11 unit tests) |
| 4 | AuthController + tests | 203d768 | AuthController, AuthControllerTest (5 tests), AuthIntegrationTest (6 tests), AbstractIntegrationTest fix |

## Test Results

- **Total tests:** 32
- **Failures:** 0
- **JwtServiceTest:** 4 unit tests (JJWT 0.12.6 generate/validate/tamper/expire)
- **RefreshTokenServiceTest:** 2 entity-level tests (grace window logic)
- **MailServiceTest:** 2 GreenMail integration tests (verification/reset email content)
- **AuthServiceTest:** 11 Mockito unit tests (token expiry/consumed/signup/login/forgot/logout edge cases)
- **AuthControllerTest:** 5 MockMvc tests (409/403/401/200/429 response codes)
- **AuthIntegrationTest:** 6 full-stack tests (signup→verify→login→refresh→logout→reset)

## Coverage

| Package | Instructions | Coverage |
|---------|-------------|----------|
| de.moviearchive.auth (AuthService only) | 443/501 | **88%** |
| de.moviearchive.auth (full package) | 688/859 | 80% |
| de.moviearchive.security | 97/153 | 63% |
| de.moviearchive.mail | 115/141 | 82% |

AuthService service layer coverage: **88%** (target: ≥85% — met).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ResponseCookie.toValueString() does not exist**
- **Found during:** Task 3 (first compile)
- **Issue:** Plan instructed `cookie.toValueString()` but `ResponseCookie` has no such method in Spring 6
- **Fix:** Changed to `cookie.toString()` — the standard `Object.toString()` on `ResponseCookie` returns the full cookie header value
- **Files modified:** `AuthService.java`
- **Commit:** 541b40e

**2. [Rule 1 - Bug] Testcontainers container stopped between test classes**
- **Found during:** Task 4 (running AuthControllerTest + AuthIntegrationTest together)
- **Issue:** `@Testcontainers` on `AbstractIntegrationTest` caused each subclass to register its own extension lifecycle; when `AuthControllerTest` finished, it stopped the shared static `PostgreSQLContainer`, causing `AuthIntegrationTest` to fail with `Connection refused`
- **Fix:** Removed `@Testcontainers` and `@Container` from `AbstractIntegrationTest`; replaced with a `static {}` block that calls `postgres.start()` once per JVM, and `@DynamicPropertySource` to register datasource URL with Spring. Container lives for the full JVM lifetime.
- **Files modified:** `AbstractIntegrationTest.java`
- **Commit:** 203d768

## Success Criteria Verification

| Criterion | Status |
|-----------|--------|
| Caddyfile has `uri strip_prefix /api` | PASS |
| V4 Flyway migration adds grace_until column | PASS |
| JwtService uses `parseSignedClaims()` | PASS |
| JwtAuthFilter has no `@Component` | PASS |
| SecurityConfig wires JwtAuthFilter via `new JwtAuthFilter(...)` | PASS |
| BCryptPasswordEncoder(12) as @Bean | PASS |
| TokenUtils.hashToken() for all token persistence | PASS |
| login() cookie path("/api/auth/refresh") | PASS |
| refresh() cookie path("/api/auth/refresh") | PASS |
| logout() clears cookie path("/api/auth/refresh") | PASS |
| forgotPassword() always 200 | PASS |
| Rate limit 429 + Retry-After on 11th request | PASS |
| graceUntil = now + 5 seconds on rotation | PASS |
| Error responses {"message":"..."} format | PASS |
| `./gradlew test --tests "de.moviearchive.auth.*"` exits 0 | PASS |
| `./gradlew test jacocoTestReport` exits 0 | PASS |
| AuthService coverage >= 85% | PASS (88%) |

## Known Stubs

None — all plan-specified functionality is wired and tested.

## Threat Flags

No new threat surface beyond the plan's threat model. All T-1-01 through T-1-10 mitigations implemented as specified.

## Self-Check: PASSED
