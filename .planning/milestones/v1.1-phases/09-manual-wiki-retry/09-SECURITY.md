---
phase: 09
slug: manual-wiki-retry
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: 2026-08-23
---

# Phase 09 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Browser → `POST /movies/{id}/retry-wiki` | Authenticated user-supplied path param `id` (UUID) crosses into the backend; must not allow cross-user movie access | Movie UUID (path param) |
| Backend → Wikipedia API (via existing `WikipediaClient`) | Outbound HTTP fetch, unchanged from Phase 8, now triggered by a client-initiated single action instead of only a batch | Film title/year (outbound query) |
| Browser → `GET /users/me` | Authenticated request; the response body crosses back to the browser and must never include sensitive `User` fields | User id (response) |
| Browser → `POST /admin/wiki-reload/{userId}` (existing, Phase 8, unchanged) | `userId` supplied by the frontend, sourced only from `GET /users/me`, which is always the caller's own id | User UUID (path param) |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-09-01 | Elevation of Privilege (IDOR) | `MovieDetailService.retryWiki` / `MovieDetailController.retryWiki` | high | mitigate | `userId` resolved from JWT via `resolveUserId(auth)`; movie loaded via `movieRepository.findByIdAndUserId(movieId, userId)` — a movie owned by another user returns 404. Verified present in code and covered by ownership tests in `MovieDetailControllerTest` (confirmed by code review and phase verifier this session). | closed |
| T-09-02 | Tampering | `@PathVariable UUID id` | low | mitigate | Spring's built-in UUID path-variable binding rejects a malformed `id` with 400 before the controller method runs — framework-level, consistent with 3 existing `@PathVariable UUID` uses in the same controller. | closed |
| T-09-03 | Denial of Service (external, against Wikipedia) | Repeated manual clicks on Retry | low | accept | Per CONTEXT.md D-01/D-02: a single human clicking one button for one movie cannot approach the ~630-simultaneous-call incident that motivated Phase 8's pacing; no rate limit added, per explicit user decision that a manual click is a deliberate one-off action. | closed |
| T-09-04 | Information Disclosure | `UserController.me` | high | mitigate | Returns `Map.of("id", id)` only — never the raw `User` JPA entity (no `@JsonIgnore` guard on its password-hash column). Verified present in code; explicit regression test (`me_responseContainsOnlyIdField`) asserts `passwordHash`/`email`/`status`/`createdAt` are absent from the response body. Confirmed by code review and phase verifier this session. | closed |
| T-09-05 | Spoofing / Broken Authentication | `GET /users/me` | low | mitigate | No new `permitAll()` entry added to `SecurityConfig` — `/users/me` falls under the existing `anyRequest().authenticated()` catch-all guarded by `JwtAuthFilter`. Verified via `SecurityConfig.java` inspection; `me_requiresAuthentication` test confirms an unauthenticated request is rejected (401/403). | closed |
| T-09-06 | Elevation of Privilege (IDOR on downstream trigger) | Settings-page call to `POST /admin/wiki-reload/{userId}` | low | accept | `GET /users/me` is self-scoped by construction (returns only the caller's own id, derived from their own JWT) — the frontend can never obtain another user's id through this endpoint. Downstream POST already enforces `assertOwnership` (Phase 8, unchanged, out of this phase's scope). | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on (high) count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| R-09-01 | T-09-03 | Manual single-click retry cannot reproduce Phase 8's batch-scale Wikipedia-hammering incident; no rate limit added per explicit user decision (CONTEXT.md D-01/D-02) | User (via CONTEXT.md discuss-phase decision) | 2026-08-23 |
| R-09-02 | T-09-06 | `GET /users/me` is self-scoped by construction; cannot be used to enumerate or target another user's id, and the downstream endpoint retains its own ownership check | Planner (09-02-PLAN.md threat model), reviewed at secure-phase | 2026-08-23 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-08-23 | 6 | 6 | 0 | /gsd-secure-phase (L1, ASVS level 1 — register authored at plan time; mitigations cross-verified via code review + phase verifier evidence gathered during execute-phase) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-08-23
