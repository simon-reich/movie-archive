# GSD Debug Knowledge Base

Resolved debug sessions. Used by `gsd-debugger` to surface known-pattern hypotheses at the start of new investigations.

---

## phase1-uat-failures — CSS variables hex vs HSL, missing auth guard, missing logout UI
- **Date:** 2026-05-16
- **Error patterns:** palette, colors, background, terracotta, hsl, css variables, hex, redirect, authenticated, login, logout, sign out, nav, navbar, single-use, consumed
- **Root cause:** (1) CSS custom properties used raw hex values but Tailwind wraps them in hsl() — invalid CSS, colors invisible. (2) Global middleware allowed all public routes unconditionally — no reverse guard for authenticated users. (3) Backend token single-use was correctly implemented — UAT false positive. (4) No navbar component in default layout — logout composable existed but had no UI entry point.
- **Fix:** Convert CSS vars to HSL space-separated components; add isAuthenticated redirect in middleware for public routes; create AppNav.vue with Sign out button and mount it conditionally in default.vue.
- **Files changed:** frontend/assets/css/main.css, frontend/middleware/auth.global.ts, frontend/layouts/default.vue, frontend/components/AppNav.vue, frontend/test/unit/middleware/auth.spec.ts

---

## auth-routing-state-bug — Access token lost on reload, stale session_email, same-email validation
- **Date:** 2026-05-16
- **Error patterns:** F5 reload, direct URL, Authorization header, access token, Pinia store, session_email cookie, useCookie, same email validation, stale cookie, email change, confirmEmail, redirect JSON, raw JSON browser
- **Root cause:** (1) Access token stored only in Pinia (in-memory) — lost on F5 or direct URL. SSR renders before client plugin restores token → race condition → API calls fire without Authorization header → silent failure. (2) No frontend guard comparing entered email to current email → same-email change sent a confirmation email; backend `confirmEmail` returned JSON 409 directly in browser instead of redirecting. (3) After successful email confirmation, `session_email` cookie was never updated in redirect response → `useCookie('session_email')` in auth store stayed stale indefinitely.
- **Fix:** (1) Backend sets non-httpOnly `access_token` cookie on login/refresh; auth store reads via `useCookie()` — works synchronously on SSR and client. (2) Frontend compares entered email to `authStore.userEmail` before submit; backend `confirmEmail` redirects `EmailAlreadyExistsException` to `/login?emailError=email-unavailable`. (3) Backend sets updated `session_email` cookie in confirmation redirect response.
- **Files changed:** AuthService.java, AuthController.java, SettingsService.java, SettingsController.java, stores/auth.ts, middleware/auth.global.ts, pages/settings.vue, pages/login.vue, plugins/auth.client.ts

---

## api-key-delete-always-fails — DELETE endpoint returns 204, ofetch parses empty body as JSON and throws
- **Date:** 2026-05-17
- **Error patterns:** delete, api key, 204, no content, ofetch, fetch error, syntax error, could not delete key, catch block, settings, tmdb, omdb
- **Root cause:** Backend DELETE /settings/api-keys/{provider} returned 204 No Content. Nuxt's $fetch (ofetch) attempted to parse the empty response body as JSON when Spring negotiated Content-Type: application/json, throwing a SyntaxError that was caught by the UI error handler and displayed as "Could not delete key. Please try again."
- **Fix:** Change backend to return ResponseEntity.ok().build() (200 OK, consistent with changePassword and other mutation endpoints). Added MSW DELETE handler and deleteApiKey unit test.
- **Files changed:** backend/src/main/java/de/moviearchive/settings/SettingsController.java, frontend/test/mocks/handlers/settings.ts, frontend/test/unit/composables/useSettings.spec.ts
---
