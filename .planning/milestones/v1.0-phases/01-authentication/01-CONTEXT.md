# Phase 1: Authentication - Context

**Gathered:** 2026-05-15
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can create accounts, verify email, log in with email + password, stay logged in via token refresh, log out, and reset forgotten passwords. This phase delivers the complete auth layer — backend (Spring Security, JWT, refresh cookies, all auth endpoints) and frontend (auth pages, route protection, token management).

**Scope anchor:** AUTH-01 through AUTH-08 from REQUIREMENTS.md. Settings, API key management, and email change are Phase 2.

</domain>

<decisions>
## Implementation Decisions

### Frontend Auth Architecture
- **D-01:** Nuxt uses **SSR with server-side middleware** for route protection. The middleware runs on every request before page rendering.
- **D-02:** Middleware does a **cookie presence check only** — it checks whether the refresh token HttpOnly cookie exists. No backend call is made server-side. If the cookie is absent, the user is redirected to `/login`.
- **D-03:** **All routes are protected by default** except auth pages (`/login`, `/signup`, `/verify-email`, `/verify-email-sent`, `/forgot-password`, `/reset-password`). Every other route redirects to `/login` if no cookie is present.

### JWT Access Token Storage
- **D-04:** The short-lived JWT access token (15 min) lives in an **in-memory Pinia store only** — no localStorage, no second cookie. Lost on page refresh.
- **D-05:** On page load / app init, the app silently calls `POST /auth/refresh` using the HttpOnly refresh cookie. If it succeeds, the Pinia store is populated with the fresh JWT and user is considered logged in. If it fails (expired or revoked cookie), the user is redirected to `/login`. This is the only mechanism to rehydrate the token after a page refresh.

### Rate Limiting (Bucket4j)
- **D-06:** Rate limit **10 requests per minute per IP** on `POST /auth/login` and `POST /auth/forgot-password`. Applied via Bucket4j in-memory token bucket, per-IP (behind Caddy).
- **D-07:** When the rate limit is hit, respond with **HTTP 429** and a `Retry-After` header (seconds until the next allowed attempt). Frontend shows: "Too many attempts. Try again in X seconds."

### Auth Error UX
- **D-08:** Auth errors are shown as **inline form errors** — appearing directly below the form or at the form level (not as toasts). Errors stay visible until the user interacts. Example: "Invalid email or password" below the login form.
- **D-09:** After successful sign-up, **redirect to `/verify-email-sent`** — a dedicated page that says "Check your inbox — click the link to verify your email." No auto-login. No inline message on the sign-up form.
- **D-10:** Backend API error response format: **`{"message": "..."}`** — flat JSON, single field. Frontend reads `.message` directly. No RFC 7807, no error codes.

### Claude's Discretion
- Auth page visual design and layout (login form, sign-up form, password reset form) — standard clean forms are expected.
- Exact form field validation messages (client-side) before API submission.
- Exact redirect targets after successful login (likely `/` or dashboard) and after logout (likely `/login`).
- `grace_until TIMESTAMPTZ` implementation on refresh_tokens for concurrent refresh race condition (technical detail for planner — see STATE.md Blockers).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Auth Flows & Endpoints
- `.claude/auth-flows.md` — Complete endpoint reference (9 endpoints), token mechanics (JWT HS256 15 min, refresh 7 days, cookie flags), mail template list, sign-up and forgot-password flows step-by-step

### Data Model
- `.claude/data-model.md` — Postgres schema for `users`, `refresh_tokens`, `email_verification_tokens`, `password_reset_tokens`, `email_change_tokens` tables. Token hash fields, `consumed_at`, TTLs.

### Requirements
- `.planning/REQUIREMENTS.md` §Authentication — AUTH-01 through AUTH-08 (the complete spec)

### Tech Stack Constraints
- `CLAUDE.md` §JWT Authentication — JJWT 0.12.6 API (`.parseSignedClaims()`, builder pattern), what NOT to do
- `CLAUDE.md` §BCrypt Password Hashing — cost factor 12, SHA-256 for token hashing
- `CLAUDE.md` §Spring @Async+@Retryable — `JwtAuthFilter` must NOT use `@Component` (instantiate directly in SecurityFilterChain)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `backend/src/main/java/de/moviearchive/user/User.java` — Entity complete: UUID id, email, passwordHash, UserStatus, createdAt. Constructor sets `PENDING_VERIFICATION` status automatically.
- `backend/src/main/java/de/moviearchive/user/UserStatus.java` — Enum exists (PENDING_VERIFICATION, ACTIVE assumed).
- `backend/src/main/java/de/moviearchive/user/UserRepository.java` — JPA repository exists.
- `backend/src/main/java/de/moviearchive/token/RefreshToken.java` — Entity complete: UUID id, user (ManyToOne), tokenHash (SHA-256), expiresAt, revoked. `isExpired()` helper exists. **Note: `grace_until` column is missing** — must be added via Flyway migration (V4+).
- Token entities exist for all types: `EmailVerificationToken`, `PasswordResetToken`, `EmailChangeToken` — each with its JPA repository.
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — Skeleton: stateless session, CSRF disabled, only `/actuator/health` permitAll. Needs `JwtAuthFilter` wired in and `/auth/**` added to permitAll list.
- `backend/src/test/java/de/moviearchive/AbstractIntegrationTest.java` — Base class for integration tests (Testcontainers).
- `backend/src/test/java/de/moviearchive/AbstractWireMockTest.java` — Base class for WireMock tests.

### Established Patterns
- UUID primary keys with `GenerationType.UUID`
- Lombok `@Getter`, `@Setter`, `@NoArgsConstructor` on entities
- Repositories extend Spring Data JPA
- Test infrastructure: Testcontainers + WireMock base classes already in place

### Integration Points
- `SecurityConfig` needs: `JwtAuthFilter` added to filter chain (before `UsernamePasswordAuthenticationFilter`), `/auth/**` and `/actuator/health` added to permitAll
- Flyway migration V4 or V5 needed to add `grace_until TIMESTAMPTZ` to `refresh_tokens` table (V1-V3 already applied)

</code_context>

<specifics>
## Specific Ideas

- The refresh-on-init pattern (D-05) means the Nuxt app plugin or `useAuth()` composable should call `/auth/refresh` before the first route renders, so SSR and client hydration are consistent.
- `JwtAuthFilter` constraint (must not be `@Component`) was explicitly noted in STATE.md as a risk — planner must instantiate it via constructor injection in `SecurityFilterChain`.
- `grace_until` on refresh_tokens handles the concurrent refresh race condition where two requests arrive with the same token within a short window. Noted in STATE.md as a blocker for planning.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 01-authentication*
*Context gathered: 2026-05-15*
