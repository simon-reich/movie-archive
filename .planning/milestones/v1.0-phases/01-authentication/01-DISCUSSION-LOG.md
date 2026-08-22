# Phase 1: Authentication - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-15
**Phase:** 01-authentication
**Areas discussed:** Frontend auth architecture, JWT access token storage, Rate limiting behavior, Auth error UX

---

## Frontend Auth Architecture

| Option | Description | Selected |
|--------|-------------|----------|
| Client-side only | Auth state in Pinia only, client-side route middleware, acceptable flash of unauthenticated content | |
| SSR with server-side middleware | Nuxt server middleware reads refresh cookie on every request, protects routes before render | ✓ |
| You decide | Leave approach to planner based on skeleton | |

**User's choice:** SSR with server-side middleware

---

| Option | Description | Selected |
|--------|-------------|----------|
| Cookie presence check only | Check if refresh token cookie exists — no backend call server-side | ✓ |
| Call /auth/refresh on every SSR request | Authoritative but adds latency to every render | |
| Validate JWT on Nuxt server side | Requires Nuxt to know JWT secret | |

**User's choice:** Cookie presence check only

---

| Option | Description | Selected |
|--------|-------------|----------|
| All routes except auth pages | Protect everything by default; only /login, /signup, /verify-email, /forgot-password, /reset-password are public | ✓ |
| Explicit allowlist only | Only protect specific routes, opt-in per page | |
| You decide | Leave to planner | |

**User's choice:** All routes except auth pages

---

## JWT Access Token Storage

| Option | Description | Selected |
|--------|-------------|----------|
| In-memory Pinia store | JWT in JS memory only, lost on refresh, rehydrated via silent /auth/refresh on init | ✓ |
| Second HttpOnly cookie | Spring Boot sets JWT as HttpOnly cookie too, fully server-driven, requires CSRF protection | |
| localStorage | Persistent but XSS risk, generally discouraged for JWTs | |

**User's choice:** In-memory Pinia store

---

| Option | Description | Selected |
|--------|-------------|----------|
| Silent /auth/refresh on app init | App calls /auth/refresh on startup using HttpOnly cookie; success = logged in, failure = redirect to /login | ✓ |
| Redirect to login immediately | If Pinia store empty on load, redirect immediately — poor UX | |
| You decide | Leave init behavior to planner | |

**User's choice:** Silent /auth/refresh on app init

---

## Rate Limiting Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Generous: 10 req/min per IP | 10 attempts/min per IP, won't block real users | ✓ |
| Tight: 5 req/min per IP | 5 attempts/min, stricter | |
| Strict: 3 req/min per IP | 3 attempts/min, OWASP-close | |

**User's choice:** 10 req/min per IP

---

| Option | Description | Selected |
|--------|-------------|----------|
| 429 with Retry-After header | Standard 429 + Retry-After seconds header | ✓ |
| 429 with no special header | Generic 429, no countdown | |
| You decide | Leave to planner | |

**User's choice:** 429 with Retry-After header

---

## Auth Error UX

| Option | Description | Selected |
|--------|-------------|----------|
| Inline form errors | Errors appear below form/field, stay visible until interaction | ✓ |
| Toast notifications | Dismissible toasts at screen edge | |
| Dedicated error page | Redirect to error page on failure | |

**User's choice:** Inline form errors

---

| Option | Description | Selected |
|--------|-------------|----------|
| Redirect to /verify-email-sent page | Dedicated page after sign-up: "Check your inbox" | ✓ |
| Inline message on sign-up form | Success banner on same page | |
| You decide | Leave post-signup UX to planner | |

**User's choice:** Redirect to /verify-email-sent page

---

| Option | Description | Selected |
|--------|-------------|----------|
| Simple {"message": "..."} | Flat JSON, single field, easy to parse | ✓ |
| RFC 7807 Problem Details | Standard format with type, title, status, detail | |
| {"code": "...", "message": "..."} | Machine-readable code + human message | |

**User's choice:** `{"message": "..."}`

---

## Claude's Discretion

- Auth page visual design and form layout
- Client-side form validation messages
- Redirect targets after successful login/logout
- `grace_until TIMESTAMPTZ` implementation detail

## Deferred Ideas

None.
