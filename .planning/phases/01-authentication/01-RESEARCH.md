# Phase 1: Authentication - Research

**Researched:** 2026-05-15
**Domain:** Spring Security + JWT + Nuxt 3 SSR Authentication
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Nuxt uses SSR with server-side middleware for route protection. Middleware runs on every request before page rendering.
- **D-02:** Middleware does a cookie presence check only — checks whether the refresh token HttpOnly cookie exists. No backend call is made server-side. If cookie absent, redirect to `/login`.
- **D-03:** All routes are protected by default except auth pages (`/login`, `/signup`, `/verify-email`, `/verify-email-sent`, `/forgot-password`, `/reset-password`). Every other route redirects to `/login`.
- **D-04:** The short-lived JWT access token (15 min) lives in an in-memory Pinia store only — no localStorage, no second cookie. Lost on page refresh.
- **D-05:** On page load / app init, the app silently calls `POST /auth/refresh` using the HttpOnly refresh cookie. If it succeeds, Pinia store is populated with fresh JWT. If it fails, user is redirected to `/login`. This is the only mechanism to rehydrate the token after page refresh.
- **D-06:** Rate limit 10 requests per minute per IP on `POST /auth/login` and `POST /auth/forgot-password` via Bucket4j in-memory token bucket.
- **D-07:** When rate limit is hit, respond with HTTP 429 and `Retry-After` header (seconds). Frontend shows: "Too many attempts. Try again in X seconds."
- **D-08:** Auth errors are shown as inline form errors — below the form or at form level (not toasts). Errors stay visible until user interacts.
- **D-09:** After successful sign-up, redirect to `/verify-email-sent`. No auto-login. No inline message on the sign-up form.
- **D-10:** Backend API error response format: `{"message": "..."}` — flat JSON, single field. Frontend reads `.message` directly. No RFC 7807, no error codes.

### Claude's Discretion

- Auth page visual design and layout (standard clean forms per UI-SPEC)
- Exact form field validation messages (client-side) before API submission
- Exact redirect targets after successful login (`/`) and after logout (`/login`)
- `grace_until TIMESTAMPTZ` implementation on refresh_tokens for concurrent refresh race condition

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | User can create account with email + password | Spring Security + BCrypt, `/auth/signup` endpoint, EmailVerificationToken entity |
| AUTH-02 | User receives verification email after sign-up; account only activated after clicking link | Spring Mail + Thymeleaf, `/auth/verify-email` endpoint, 24h token TTL, `PENDING_VERIFICATION` → `ACTIVE` transition |
| AUTH-03 | User can log in with email + password (ACTIVE accounts only) | `/auth/login` endpoint, status guard, JWT issuance |
| AUTH-04 | User receives JWT access token (15 min) + refresh token as HttpOnly cookie (7 days) | JJWT 0.12.6 builder, `ResponseCookie` with HttpOnly+Secure+SameSite=Strict |
| AUTH-05 | Refresh token rotated on `/auth/refresh` (old revoked, new issued) | RefreshToken rotation logic, `grace_until` for race condition |
| AUTH-06 | User can log out (refresh token revoked) | `/auth/logout`, JWT filter for auth, mark token `revoked=true` |
| AUTH-07 | User can request password reset email (always 200 OK — enumeration protection) | PasswordResetToken, always-200 pattern, 1h TTL |
| AUTH-08 | User can reset password with token; all refresh tokens for account revoked | `/auth/reset-password`, bulk revoke by user_id |
</phase_requirements>

---

## Summary

Phase 1 implements the complete authentication layer — all nine backend endpoints and six frontend pages. The backend stack (Spring Security 6, JJWT 0.12.6, BCrypt, Spring Mail + Thymeleaf, Bucket4j) is already declared in `build.gradle.kts`. The frontend stack (Nuxt 3, Pinia, radix-vue, lucide-vue-next, MSW) is already installed. All database entities exist from Phase 0 work (V1–V3 migrations applied), with one gap: the `grace_until` column is absent from `refresh_tokens` and must be added via a V4 Flyway migration.

The implementation divides cleanly into three tracks: (1) backend service + filter + controller layer, (2) Flyway migration + SecurityConfig wiring, and (3) frontend pages + composables + Pinia store. Test infrastructure (Testcontainers, GreenMail, WireMock base classes, MSW handlers) is already in place — the phase adds feature tests on top of this foundation.

The primary planning risk is the `JwtAuthFilter` instantiation constraint: it MUST NOT be a `@Component` bean (causes double-registration in Spring Security's filter chain). It must be instantiated via constructor in the `SecurityFilterChain` bean, passing `JwtService` as a constructor argument.

**Primary recommendation:** Implement backend in two waves (infrastructure + service layer, then controllers + tests), frontend in one wave (pages + composables + Pinia), with the Flyway migration as the first task in wave 1.

---

## Standard Stack

### Core (all already in build.gradle.kts / package.json)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JJWT | 0.12.6 | JWT creation + validation | Project-locked; 0.12.x API (`parseSignedClaims`, builder) confirmed in CLAUDE.md |
| Spring Security | BOM-managed (6.x) | Filter chain, `SecurityFilterChain`, `BCryptPasswordEncoder` | Managed by Spring Boot 3.5.0 BOM |
| BCryptPasswordEncoder | BOM-managed | Password hashing (cost 12) | Project decision; SHA-256 for tokens only |
| Spring Mail + Thymeleaf | BOM-managed | Email dispatch; HTML templates | Already declared in build.gradle.kts |
| Bucket4j Core | 8.10.1 | In-memory rate limiting per IP | Already in build.gradle.kts; decided in D-06 |
| Pinia | 2.3.1 | In-memory JWT store (D-04) | Already installed; `@pinia/nuxt` 0.9.0 wired in nuxt.config.ts |
| MSW | 2.7.5 | Mock backend in FE tests | Already installed; handlers.ts + server.ts scaffold exists |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| GreenMail | 2.1.3 | In-process SMTP for mail tests | All email verification + password reset tests |
| Testcontainers (PostgreSQL) | BOM-managed | Real DB in integration tests | Auth integration flow tests |
| Spring Security Test | BOM-managed | `@WithMockUser`, `SecurityMockMvcRequestPostProcessors` | Controller slice tests |
| `@nuxt/test-utils` | 3.17.2 | Nuxt component + page mounting in Vitest | FE integration tests |
| Vue Test Utils | 2.4.6 | Component-level assertions | Component unit tests |

**No new dependencies required for this phase.** All libraries are already declared.

---

## Architecture Patterns

### Backend Package Structure (new packages to create)

```
backend/src/main/java/de/moviearchive/
├── auth/
│   ├── AuthController.java          # 9 REST endpoints
│   ├── AuthService.java             # signup, login, refresh, logout, verify, reset
│   ├── dto/
│   │   ├── SignupRequest.java
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java       # { accessToken, email }
│   │   ├── RefreshResponse.java
│   │   ├── VerifyEmailRequest.java
│   │   ├── ForgotPasswordRequest.java
│   │   └── ResetPasswordRequest.java
│   └── RateLimitService.java        # Bucket4j per-IP buckets
├── security/
│   ├── JwtService.java              # token creation + validation
│   ├── JwtAuthFilter.java           # OncePerRequestFilter — NOT @Component
│   └── UserDetailsServiceImpl.java  # loads User by email for Spring Security
├── mail/
│   └── MailService.java             # sends welcome-verify.html, password-reset.html
└── config/
    └── SecurityConfig.java          # EXISTING — needs JwtAuthFilter + permitAll expansion
```

### Frontend Structure (new directories)

```
frontend/
├── pages/
│   ├── login.vue
│   ├── signup.vue
│   ├── verify-email-sent.vue
│   ├── verify-email.vue
│   ├── forgot-password.vue
│   └── reset-password.vue
├── composables/
│   └── useAuth.ts                   # login, signup, logout, refresh, verify, reset
├── stores/
│   └── auth.ts                      # Pinia: { accessToken, user } — in-memory only
├── middleware/
│   └── auth.global.ts               # server-side cookie presence check (D-01, D-02, D-03)
├── plugins/
│   └── auth.client.ts               # calls /auth/refresh on app init (D-05)
└── components/
    ├── AuthCard.vue
    ├── FormField.vue
    ├── InputText.vue
    ├── ButtonPrimary.vue
    ├── FormErrorBanner.vue
    └── SpinnerIcon.vue
```

### Pattern 1: JwtAuthFilter — Constructor Injection (NOT @Component)

**What:** `OncePerRequestFilter` that extracts the Bearer token from `Authorization` header, validates it via `JwtService`, and sets the `SecurityContext`.

**Critical constraint:** Must NOT be annotated `@Component`. Spring Boot's security auto-configuration would register it twice — once as a Spring bean and once manually in `SecurityFilterChain`. Instantiate via `new` inside the `SecurityFilterChain` bean.

```java
// Source: CLAUDE.md §JWT Authentication + CONTEXT.md §JwtAuthFilter constraint
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService,
                                           UserDetailsServiceImpl userDetailsService) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService, userDetailsService);
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/auth/**",
                    "/actuator/health"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}
```
[VERIFIED: codebase — SecurityConfig.java read directly]

### Pattern 2: JJWT 0.12.6 — Correct API

```java
// Source: CLAUDE.md §JWT Authentication — 0.12.x API
// Token creation
String token = Jwts.builder()
    .subject(userId.toString())
    .claim("email", email)
    .issuedAt(new Date())
    .expiration(new Date(System.currentTimeMillis() + expirationMs))
    .signWith(getSigningKey())
    .compact();

// Token validation
Claims claims = Jwts.parser()
    .verifyWith(getSigningKey())
    .build()
    .parseSignedClaims(token)   // NOT parseClaimsJws — removed in 0.12.x
    .getPayload();
```
[VERIFIED: CLAUDE.md §JWT Authentication §What NOT to do with JJWT]

### Pattern 3: Refresh Token Rotation with grace_until

The `grace_until` column handles the concurrent refresh race condition: two requests arrive within milliseconds with the same refresh token cookie (e.g., two browser tabs on page load). Without grace, the second request finds the token already revoked and logs the user out.

**Solution:** On rotation, instead of immediately marking the old token `revoked=true`, set `grace_until = now() + 5 seconds`. Lookup allows tokens where `revoked=false OR grace_until > now()`. Issue new token. Any subsequent request with the old token after `grace_until` is past treats it as revoked.

**Flyway migration required:** V4 adds `grace_until TIMESTAMPTZ` to `refresh_tokens`.

```sql
-- V4__add_grace_until_to_refresh_tokens.sql
ALTER TABLE refresh_tokens ADD COLUMN grace_until TIMESTAMPTZ;
```

```java
// RefreshToken entity — add field
@Column(name = "grace_until")
private Instant graceUntil;  // nullable — null means immediately revoked

// Repository query
Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);
// Replace with:
@Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :hash AND (rt.revoked = false OR rt.graceUntil > :now)")
Optional<RefreshToken> findValidToken(@Param("hash") String hash, @Param("now") Instant now);
```
[ASSUMED — grace period logic; specific 5-second window is reasonable default but planner may adjust]

### Pattern 4: HttpOnly Refresh Cookie

```java
// Source: CLAUDE.md §Token Mechanics
ResponseCookie cookie = ResponseCookie.from("refresh_token", rawRefreshToken)
    .httpOnly(true)
    .secure(true)               // always; Caddy terminates TLS
    .sameSite("Strict")
    .maxAge(Duration.ofDays(7))
    .path("/auth/refresh")      // scope cookie to refresh endpoint only
    .build();
response.addHeader(HttpHeaders.SET_COOKIE, cookie.toValueString());
```
[ASSUMED — `ResponseCookie` from `org.springframework.http`; standard Spring Web pattern]

### Pattern 5: SHA-256 Token Hash

All single-use tokens (email verification, password reset) are stored as SHA-256 hashes. The raw token travels in the email link; only the hash is stored in PostgreSQL.

```java
// Source: CLAUDE.md §SHA-256 token hashing
public static String hashToken(String rawToken) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
}
```
[VERIFIED: CLAUDE.md §BCrypt Password Hashing §SHA-256 token hashing]

### Pattern 6: Bucket4j Rate Limiter (per IP)

```java
// Source: CLAUDE.md §Supporting Libraries — Bucket4j 8.10.1
// D-06: 10 req/min per IP on /auth/login and /auth/forgot-password
private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

private Bucket resolveBucket(String ip) {
    return buckets.computeIfAbsent(ip, k ->
        Bucket.builder()
            .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
            .build());
}

// In controller / interceptor:
Bucket bucket = resolveBucket(clientIp);
if (!bucket.tryConsume(1)) {
    // respond 429 with Retry-After header
    long waitSeconds = bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() / 1_000_000_000L;
    response.setHeader("Retry-After", String.valueOf(waitSeconds));
    return ResponseEntity.status(429).body(Map.of("message", "Too many attempts"));
}
```
[VERIFIED: build.gradle.kts — bucket4j-core:8.10.1 confirmed; API pattern ASSUMED from training knowledge]

### Pattern 7: Nuxt Server-Side Auth Middleware

```typescript
// middleware/auth.global.ts — D-01, D-02, D-03
// Source: CONTEXT.md D-01 through D-03
export default defineNuxtRouteMiddleware((to) => {
  const publicRoutes = [
    '/login', '/signup', '/verify-email',
    '/verify-email-sent', '/forgot-password', '/reset-password'
  ]
  if (publicRoutes.includes(to.path)) return

  // Cookie presence check only — no backend call server-side (D-02)
  const refreshCookie = useCookie('refresh_token')
  if (!refreshCookie.value) {
    return navigateTo('/login')
  }
})
```
[ASSUMED — useCookie composable availability in server middleware; standard Nuxt 3 pattern]

### Pattern 8: Auth Plugin (D-05 — refresh on init)

```typescript
// plugins/auth.client.ts — D-05
// Runs only on client, before first route render
export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  // Attempt silent refresh; failure sets no token → middleware catches on next nav
  try {
    await authStore.refresh()
  } catch {
    // Token expired / revoked — store remains empty, middleware will redirect
  }
})
```
[ASSUMED — Nuxt plugin lifecycle; standard pattern for hydrating auth state]

### Anti-Patterns to Avoid

- **`@Component` on `JwtAuthFilter`:** Causes double registration. Filter is added twice to the chain — first by Spring Boot's component scan, second by `addFilterBefore()`. Results in JWT being validated twice per request. Use constructor instantiation only. [VERIFIED: CLAUDE.md + CONTEXT.md §Existing Code Insights]
- **`parseClaimsJws()` in JJWT:** Removed in 0.12.x — compile error. Use `parseSignedClaims()`. [VERIFIED: CLAUDE.md §What NOT to do with JJWT]
- **`.setSubject()` / `.setExpiration()` in JJWT:** Removed in 0.12.x. Use `.subject()` / `.expiration()`. [VERIFIED: CLAUDE.md]
- **Storing raw refresh token in DB:** Store SHA-256 hash only. Raw token lives only in the cookie. [VERIFIED: CLAUDE.md §Tokens]
- **Auto-login after sign-up:** D-09 locks this out — show `/verify-email-sent`, never issue JWT on signup. [VERIFIED: CONTEXT.md D-09]
- **Returning 404 when forgot-password email not found:** Always 200 OK — enumeration protection (AUTH-07). [VERIFIED: REQUIREMENTS.md AUTH-07]
- **Blocking `/auth/refresh` behind JWT auth:** The refresh endpoint must be `permitAll()` — it uses the cookie, not the Bearer header.
- **`localStorage` for access token:** D-04 explicitly prohibits this. In-memory Pinia store only.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Password hashing | Custom hash + salt | `BCryptPasswordEncoder(12)` | BCrypt handles salt, work factor, timing safety |
| JWT creation + parsing | Manual Base64 + HMAC | JJWT 0.12.6 | Handles algorithm negotiation, claims, expiry |
| IP rate limiting | Custom counter + timestamps | Bucket4j token bucket | Handles burst vs. sustained, thread-safe, correct refill math |
| HTTP cookie creation | Manual `Set-Cookie` header | `ResponseCookie` (Spring Web) | Handles encoding, `SameSite`, `HttpOnly` flag correctly |
| Email HTML rendering | String concatenation | Thymeleaf templates | Context variable injection, HTML escaping |
| Security filter chain | Custom servlet filter chain | Spring Security `SecurityFilterChain` | CSRF, session, `SecurityContext` propagation |

---

## Common Pitfalls

### Pitfall 1: JwtAuthFilter Double-Registration
**What goes wrong:** Filter executes twice per request; JWT is validated twice; logs are duplicated; request may fail if the filter modifies request state.
**Why it happens:** Spring Boot's component scan registers `@Component` beans as generic servlet filters automatically. `addFilterBefore()` adds it a second time.
**How to avoid:** No `@Component` on `JwtAuthFilter`. Instantiate via `new JwtAuthFilter(jwtService, userDetailsService)` inside `SecurityFilterChain` bean. [VERIFIED: CLAUDE.md + CONTEXT.md]
**Warning signs:** Filter log statements appearing twice per request in dev console.

### Pitfall 2: JJWT 0.12.x API Mismatch
**What goes wrong:** Compile error on `parseClaimsJws()`, `setSubject()`, `setExpiration()`.
**Why it happens:** 0.12.x removed the deprecated fluent-setter API.
**How to avoid:** Use CLAUDE.md §JWT Authentication §Correct 0.12.x API as the authoritative reference.
**Warning signs:** `cannot find symbol` compile error on any method starting with `.set` or `.parse...Jws`.

### Pitfall 3: Refresh Endpoint Behind JWT Guard
**What goes wrong:** `/auth/refresh` returns 401 because the access token (which it's supposed to renew) is already expired.
**Why it happens:** `anyRequest().authenticated()` catches `/auth/refresh` if it's not explicitly permitted.
**How to avoid:** `/auth/**` in `permitAll()` — the refresh endpoint is protected by the HttpOnly cookie + DB lookup, not by JWT.

### Pitfall 4: Concurrent Refresh Race (Two Browser Tabs)
**What goes wrong:** User opens two tabs simultaneously. Both trigger `/auth/refresh` on init. The first call rotates the token. The second call finds the token already revoked and logs the user out.
**Why it happens:** Token rotation marks old token revoked before the second request arrives.
**How to avoid:** Implement `grace_until` column (V4 migration). The planner must include `grace_until` as the first backend task. [VERIFIED: CONTEXT.md §Blockers/Concerns + STATE.md]

### Pitfall 5: `SameSite=Strict` Breaking Password Reset Links
**What goes wrong:** User clicks the password reset link in their email client (external navigation). Browser does not send the cookie on the initial GET request because `SameSite=Strict` blocks cross-site navigations.
**Why it happens:** Reset links are top-level navigations from a different site (email client).
**Impact for this phase:** Reset page (`/reset-password`) is a public page — it reads the token from the URL query parameter, not from a cookie. This pitfall does not apply here because the password reset flow does not use a cookie. The JWT access token refresh cookie is Strict, which is correct. Documenting for awareness. [ASSUMED — standard SameSite=Strict behavior; no impact on this phase's design]

### Pitfall 6: Nuxt Plugin vs Middleware Execution Order
**What goes wrong:** `auth.client.ts` plugin fires after the global middleware, so the middleware redirects to `/login` before the refresh call succeeds.
**Why it happens:** Nuxt middleware runs before page rendering; plugins run during app initialization which may overlap.
**How to avoid:** The middleware performs cookie presence check only (D-02) — it does not call the backend. The redirect decision is: "cookie present → allow render, plugin will hydrate the store." "Cookie absent → redirect." This keeps middleware and plugin independent. [VERIFIED: CONTEXT.md D-02]

### Pitfall 7: GreenMail Not Receiving Thymeleaf Emails in Tests
**What goes wrong:** Integration test asserts mail was sent but GreenMail inbox is empty.
**Why it happens:** GreenMail extension must start before Spring context; if `@RegisterExtension` is static, context may start on wrong SMTP port.
**How to avoid:** Use `@RegisterExtension static GreenMailExtension` with `ServerSetupTest.SMTP`. Configure `spring.mail.host=localhost` and `spring.mail.port=3025` (GreenMail default) in `application-test.properties`. [ASSUMED — GreenMail integration pattern; verify against GreenMail 2.1.3 docs]

---

## Code Examples

### Signup Flow — Service Method Sketch

```java
// Source: auth-flows.md §Sign-Up & Email Verification
@Transactional
public void signup(SignupRequest req) {
    if (userRepository.existsByEmail(req.email())) {
        throw new EmailAlreadyExistsException();
    }
    User user = new User(req.email(), passwordEncoder.encode(req.password()));
    // User constructor sets status = PENDING_VERIFICATION (VERIFIED: User.java)
    userRepository.save(user);

    String rawToken = UUID.randomUUID().toString();
    String hash = TokenUtils.hashToken(rawToken);
    emailVerificationTokenRepository.save(
        new EmailVerificationToken(user, hash, Instant.now().plus(24, ChronoUnit.HOURS)));
    mailService.sendVerificationEmail(user.getEmail(), rawToken);
}
```

### Login Flow — JWT + Cookie Issuance

```java
// Source: auth-flows.md §Token Mechanics
@Transactional
public LoginResponse login(LoginRequest req, HttpServletResponse response) {
    User user = userRepository.findByEmail(req.email())
        .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
    if (user.getStatus() != UserStatus.ACTIVE) {
        throw new AccountNotActiveException(); // → 403
    }
    if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
        throw new BadCredentialsException("Invalid email or password");
    }

    String accessToken = jwtService.generateAccessToken(user);
    String rawRefresh = UUID.randomUUID().toString();
    refreshTokenRepository.save(new RefreshToken(
        user, TokenUtils.hashToken(rawRefresh),
        Instant.now().plus(7, ChronoUnit.DAYS)));

    ResponseCookie cookie = ResponseCookie.from("refresh_token", rawRefresh)
        .httpOnly(true).secure(true).sameSite("Strict")
        .maxAge(Duration.ofDays(7)).path("/auth/refresh").build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toValueString());

    return new LoginResponse(accessToken, user.getEmail());
}
```
[ASSUMED — method signatures; verified flow against auth-flows.md]

### Frontend: Pinia Auth Store

```typescript
// stores/auth.ts — D-04: in-memory only, no localStorage
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const userEmail = ref<string | null>(null)

  function setAuth(token: string, email: string) {
    accessToken.value = token
    userEmail.value = email
  }

  function clearAuth() {
    accessToken.value = null
    userEmail.value = null
  }

  async function refresh() {
    // POST /auth/refresh — credentials: 'include' sends HttpOnly cookie
    const data = await $fetch<{ accessToken: string; email: string }>('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
    setAuth(data.accessToken, data.email)
  }

  const isAuthenticated = computed(() => !!accessToken.value)

  return { accessToken, userEmail, isAuthenticated, setAuth, clearAuth, refresh }
})
```
[ASSUMED — Pinia 2.x composition API style; standard pattern]

---

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| `parseClaimsJws()` (JJWT < 0.12) | `parseSignedClaims()` (JJWT 0.12.x) | Compile error if old API used |
| `.setSubject()` / `.setExpiration()` fluent setters | `.subject()` / `.expiration()` builder methods | Compile error if old API used |
| `RestClientTransport` (OpenSearch client, deprecated) | `ApacheHttpClient5Transport` | Not relevant to auth phase but noted for downstream phases |
| Spring Security `WebSecurityConfigurerAdapter` | `SecurityFilterChain` bean | `WebSecurityConfigurerAdapter` removed in Spring Security 6 |

**Deprecated/outdated:**
- `WebSecurityConfigurerAdapter`: Removed in Spring Security 6. Already using `SecurityFilterChain` bean pattern in `SecurityConfig.java`. [VERIFIED: SecurityConfig.java read directly]
- JJWT < 0.12 API: Multiple methods renamed/removed. [VERIFIED: CLAUDE.md]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `grace_until` grace period of 5 seconds is sufficient to cover concurrent refresh race | Architecture Patterns — Pattern 3 | Too short → race condition persists; too long → security window; adjust value at planning time |
| A2 | `ResponseCookie.from(...)` pattern for HttpOnly cookie creation | Pattern 4 | Minor — standard Spring Web API; would surface at compile time |
| A3 | `useCookie()` in Nuxt server middleware reads request cookies correctly | Pattern 7 | If wrong, middleware cannot detect cookie presence; test in dev |
| A4 | `auth.client.ts` plugin runs before user can interact but after middleware | Pattern 8 | Plugin/middleware ordering may need `plugins/auth.server.ts` variant |
| A5 | Bucket4j `ConcurrentHashMap<String, Bucket>` per-IP pattern is thread-safe | Pattern 6 | Already thread-safe via `computeIfAbsent`; low risk |
| A6 | GreenMail `ServerSetupTest.SMTP` default port is 3025 | Pitfall 7 | Port conflict in test environment; verify against GreenMail 2.1.3 docs |
| A7 | `path("/auth/refresh")` cookie path scope works correctly with Caddy proxy stripping `/api` prefix | Pattern 4 | If Caddy strips the path, cookie scope must be `/` or adjusted; verify against Caddy config |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 25 (toolchain) | Backend compilation | To verify at build time | toolchain in build.gradle.kts | — |
| PostgreSQL 16 | Testcontainers (pulled at test time) | ✓ (Docker image) | 16-alpine | — |
| Docker | Testcontainers | Assumed ✓ | — | No fallback — required for Testcontainers |
| GreenMail 2.1.3 | Mail tests | ✓ (declared in build.gradle.kts) | 2.1.3 | — |
| Mailpit | E2E tests (Phase 7) | Not relevant yet | — | — |

**Missing dependencies with no fallback:** None — all required libraries already declared in build.gradle.kts and package.json.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework (BE) | JUnit 5 + Mockito + Testcontainers + GreenMail + MockMvc |
| Framework (FE) | Vitest 3.1.3 + Vue Test Utils 2.4.6 + MSW 2.7.5 |
| Config file (BE) | `backend/build.gradle.kts` (JUnit Platform via `useJUnitPlatform()`) |
| Config file (FE) | `frontend/vitest.config.ts` |
| Quick run (BE) | `./gradlew test --tests "de.moviearchive.auth.*"` |
| Full suite (BE) | `./gradlew test jacocoTestReport` |
| Quick run (FE) | `pnpm test --run` |
| Full suite (FE) | `pnpm test --run --coverage` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AUTH-01 | Signup creates user with PENDING_VERIFICATION status | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldCreateUser*"` | ❌ Wave 0 |
| AUTH-01 | Signup returns 409 for duplicate email | Web/Controller | `./gradlew test --tests "*AuthControllerTest.shouldReturn409*"` | ❌ Wave 0 |
| AUTH-02 | Verify-email sets status ACTIVE, marks token consumed | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldVerifyEmail*"` | ❌ Wave 0 |
| AUTH-02 | Verification mail sent with token link (GreenMail) | Mail | `./gradlew test --tests "*MailServiceTest.shouldSendVerification*"` | ❌ Wave 0 |
| AUTH-02 | Expired/consumed token returns 400 | Unit/Service | `./gradlew test --tests "*AuthServiceTest.shouldRejectExpiredToken*"` | ❌ Wave 0 |
| AUTH-03 | Login rejects PENDING_VERIFICATION account with 403 | Web/Controller | `./gradlew test --tests "*AuthControllerTest.shouldRejectUnverified*"` | ❌ Wave 0 |
| AUTH-03 | Login with wrong password returns 401 | Web/Controller | `./gradlew test --tests "*AuthControllerTest.shouldRejectBadPassword*"` | ❌ Wave 0 |
| AUTH-04 | Successful login returns accessToken + HttpOnly cookie | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldLogin*"` | ❌ Wave 0 |
| AUTH-05 | Refresh rotates token (old revoked, new issued) | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldRotateRefreshToken*"` | ❌ Wave 0 |
| AUTH-05 | Concurrent refresh within grace window succeeds | Unit/Service | `./gradlew test --tests "*RefreshTokenServiceTest.shouldHandleGracePeriod*"` | ❌ Wave 0 |
| AUTH-06 | Logout marks refresh token revoked | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldLogout*"` | ❌ Wave 0 |
| AUTH-07 | Forgot-password always returns 200 (enum protection) | Web/Controller | `./gradlew test --tests "*AuthControllerTest.shouldReturn200ForUnknownEmail*"` | ❌ Wave 0 |
| AUTH-07 | Reset mail sent when email exists (GreenMail) | Mail | `./gradlew test --tests "*MailServiceTest.shouldSendPasswordReset*"` | ❌ Wave 0 |
| AUTH-08 | Reset password sets new hash, revokes all refresh tokens | Integration | `./gradlew test --tests "*AuthIntegrationTest.shouldResetPassword*"` | ❌ Wave 0 |
| AUTH-08 | Reset with expired/used token returns 400 | Unit/Service | `./gradlew test --tests "*AuthServiceTest.shouldRejectExpiredResetToken*"` | ❌ Wave 0 |
| D-06 | Rate limiter returns 429 + Retry-After on 11th request | Web/Controller | `./gradlew test --tests "*AuthControllerTest.shouldRateLimitLogin*"` | ❌ Wave 0 |
| FE | Login page submits form → store populated → redirect / | FE Integration | `pnpm test --run test/unit/pages/login.spec.ts` | ❌ Wave 0 |
| FE | Middleware redirects unauthenticated to /login | FE Unit | `pnpm test --run test/unit/middleware/auth.spec.ts` | ❌ Wave 0 |
| FE | Auth plugin calls /auth/refresh on init | FE Unit | `pnpm test --run test/unit/plugins/auth.spec.ts` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew test --tests "de.moviearchive.auth.*"` (BE) / `pnpm test --run test/unit/` (FE)
- **Per wave merge:** `./gradlew test jacocoTestReport` (full BE suite with coverage)
- **Phase gate:** Full BE suite green + coverage ≥ 75% overall, ≥ 85% service layer, before `/gsd-verify-work`

### Wave 0 Gaps

All test files are new — no existing auth tests exist:

- [ ] `backend/src/test/java/de/moviearchive/auth/AuthControllerTest.java` — covers AUTH-01/03/06/07, D-06
- [ ] `backend/src/test/java/de/moviearchive/auth/AuthIntegrationTest.java` — covers AUTH-01/02/04/05/06/08 (Testcontainers + GreenMail)
- [ ] `backend/src/test/java/de/moviearchive/auth/AuthServiceTest.java` — covers AUTH-02/08 (unit, Mockito)
- [ ] `backend/src/test/java/de/moviearchive/security/JwtServiceTest.java` — JWT creation + validation unit tests
- [ ] `backend/src/test/java/de/moviearchive/mail/MailServiceTest.java` — AUTH-02/07 mail tests (GreenMail)
- [ ] `backend/src/test/java/de/moviearchive/token/RefreshTokenServiceTest.java` — AUTH-05 grace period
- [ ] `backend/src/test/resources/application-test.properties` — GreenMail SMTP port, test JWT secret
- [ ] `frontend/test/unit/stores/auth.spec.ts` — Pinia store unit tests
- [ ] `frontend/test/unit/composables/useAuth.spec.ts` — composable unit tests
- [ ] `frontend/test/unit/middleware/auth.spec.ts` — middleware redirect logic
- [ ] `frontend/test/unit/plugins/auth.spec.ts` — plugin refresh-on-init
- [ ] `frontend/test/unit/pages/login.spec.ts` — login page integration (MSW)
- [ ] `frontend/test/unit/pages/signup.spec.ts` — signup page integration (MSW)
- [ ] `frontend/test/unit/pages/forgot-password.spec.ts` — forgot password page (MSW)
- [ ] `frontend/test/unit/pages/reset-password.spec.ts` — reset password page (MSW)
- [ ] `frontend/test/unit/pages/verify-email.spec.ts` — verify email page (MSW)

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | BCryptPasswordEncoder(12), JJWT 0.12.6, single-use tokens |
| V3 Session Management | yes | HttpOnly + Secure + SameSite=Strict cookie; rotation on every refresh; revoke on logout and password reset |
| V4 Access Control | yes | `SecurityFilterChain` — `anyRequest().authenticated()` with `JwtAuthFilter`; Nuxt middleware cookie guard |
| V5 Input Validation | yes | Spring `@Valid` + Bean Validation on DTOs (email format, password length ≥ 8) |
| V6 Cryptography | yes | BCrypt (passwords), SHA-256 (tokens), JJWT HS256 (access token); AES-256-GCM is Phase 2 |

### Known Threat Patterns for Auth Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Brute-force login | Elevation of Privilege | Bucket4j 10 req/min per IP on `/auth/login` (D-06) |
| Account enumeration via forgot-password | Information Disclosure | Always 200 OK on `/auth/forgot-password` regardless of email existence (AUTH-07) |
| Refresh token theft → session hijack | Elevation of Privilege | HttpOnly + Secure + SameSite=Strict cookie; rotation on use; revoke on password reset |
| Replay attack on single-use tokens | Elevation of Privilege | `consumed_at` field; repository lookup checks `consumed_at IS NULL` |
| Expired token accepted | Elevation of Privilege | `isExpired()` check on `RefreshToken`; JJWT expiry claim validation for JWT |
| JWT secret in config | Information Disclosure | `${JWT_SECRET}` ENV var; min 32 chars enforced by documentation |
| Concurrent refresh session drop | Denial of Service | `grace_until` window (V4 migration) |
| CSRF (stateless API) | Tampering | CSRF disabled (`AbstractHttpConfigurer::disable`); stateless session; Bearer token in header |

---

## Open Questions

1. **Cookie path scope with Caddy proxy**
   - What we know: Caddy routes `/api/*` → Spring Boot. The refresh cookie has `path=/auth/refresh`.
   - What's unclear: Does the browser send the cookie to `/api/auth/refresh` (Caddy path) or does the cookie path need to be `/api/auth/refresh`?
   - Recommendation: Set cookie path to `/auth/refresh` and verify in manual testing that Caddy forwards it correctly. If Caddy strips `/api`, the path `/auth/refresh` matches the upstream path — this is correct. If the browser sees the full URL as `/api/auth/refresh`, the cookie with `path=/auth/refresh` will NOT be sent (path mismatch). May need `path=/api/auth/refresh` or `path=/`. Flag for planner to verify against `docker-compose.yml` Caddy configuration.

2. **`application-test.properties` — GreenMail port**
   - What we know: GreenMail defaults to port 3025 for SMTP in test mode (`ServerSetupTest.SMTP`).
   - What's unclear: Whether `AbstractIntegrationTest.java` already configures Spring's mail port, or whether a new `application-test.properties` is needed.
   - Recommendation: Create `application-test.properties` in Wave 0 with `spring.mail.host=localhost` and `spring.mail.port=3025`.

3. **`RefreshTokenRepository` — `findValidToken` query**
   - What we know: Current `RefreshTokenRepository` extends `JpaRepository`. The `grace_until` column does not yet exist.
   - What's unclear: Whether JPQL or native SQL query is preferable for the `grace_until` null-safe lookup.
   - Recommendation: Use `@Query` with JPQL — `WHERE rt.revoked = false OR (rt.graceUntil IS NOT NULL AND rt.graceUntil > :now)`.

---

## Project Constraints (from CLAUDE.md)

All directives from CLAUDE.md that apply to this phase:

| Directive | Impact on Phase 1 |
|-----------|------------------|
| English-only: UI, code, logs, tests, commit messages | All auth page copy, error messages, log statements, and test names in English |
| Tests ship with the feature — no feature merge without tests | Every backend endpoint and FE page ships with tests in the same plan wave |
| External APIs always mocked in tests (WireMock BE, MSW FE) | No real SMTP in tests — GreenMail only. No real backend from FE tests — MSW only |
| JJWT 0.12.6 — `parseSignedClaims()`, builder pattern, no deprecated methods | JwtService must use only the 0.12.x API documented in CLAUDE.md |
| BCrypt cost factor 12 — `new BCryptPasswordEncoder(12)` | SecurityConfig `@Bean` must pass `12` explicitly |
| SHA-256 for token hashing — never store raw token | TokenUtils.hashToken() used everywhere a token is persisted |
| Tokens: single-use via `consumed_at` | All token redemption queries must check `consumed_at IS NULL` |
| API keys at rest: AES-256-GCM (Phase 2) | NOT in this phase — out of scope |
| `JwtAuthFilter` must NOT use `@Component` | Instantiate in `SecurityFilterChain` bean — explicitly documented blocker |
| `jwt.secret` from `${JWT_SECRET}` ENV var, min 32 chars | Test environment must supply a 32-char test secret in `application-test.properties` |
| Commit format: `MOV-XX: <summary>` + transition Jira to Done | Every commit follows this format; Jira transition happens automatically after each ticket |

---

## Sources

### Primary (HIGH confidence)
- `build.gradle.kts` — authoritative dependency versions, confirmed by direct file read
- `CLAUDE.md` — JJWT 0.12.x API, BCrypt configuration, JwtAuthFilter constraint, token hashing rules
- `.claude/auth-flows.md` — endpoint reference, token mechanics, mail templates, flow steps
- `.claude/data-model.md` — PostgreSQL schema, token table structures
- `.claude/test-strategy.md` — test types, tooling, coverage targets, fixture locations
- `.planning/phases/01-authentication/01-CONTEXT.md` — all locked decisions D-01 through D-10
- `.planning/phases/01-authentication/01-UI-SPEC.md` — component inventory, copywriting contract, color tokens
- `backend/src/main/java/de/moviearchive/` — existing entity and repository files read directly
- `backend/src/main/resources/application.properties` — ENV var names, JWT expiration values
- `frontend/package.json` — installed package versions
- `frontend/nuxt.config.ts` — modules, runtimeConfig

### Secondary (MEDIUM confidence)
- Standard Spring Security 6 `SecurityFilterChain` pattern — well-established, consistent with existing SecurityConfig.java
- GreenMail 2.1.3 `@RegisterExtension` pattern — documented in test-strategy.md

### Tertiary (LOW confidence)
- Bucket4j per-IP `ConcurrentHashMap<String, Bucket>` pattern (A5) — training knowledge, standard usage
- Nuxt 3 `useCookie()` in server middleware (A3) — training knowledge, standard Nuxt 3 pattern
- GreenMail default SMTP port 3025 (A6) — training knowledge; verify against GreenMail docs

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries confirmed in build.gradle.kts / package.json via direct file read
- Architecture: HIGH for backend (entities, SecurityConfig, test base classes verified); MEDIUM for frontend (Nuxt 3 patterns are training knowledge)
- Pitfalls: HIGH — JwtAuthFilter and JJWT API pitfalls verified in CLAUDE.md; others are well-established patterns

**Research date:** 2026-05-15
**Valid until:** 2026-06-15 (stable stack; Spring Boot 3.5.0 BOM-managed dependencies)
