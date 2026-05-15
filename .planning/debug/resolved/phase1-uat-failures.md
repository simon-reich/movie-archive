---
status: resolved
trigger: "Investigate and fix 4 Phase 1 UAT failures: phase1-uat-failures"
created: 2026-05-16T00:00:00Z
updated: 2026-05-16T00:02:00Z
---

## Current Focus

hypothesis: all root causes confirmed and fixed
test: 56 unit tests pass, commit 364d19e
expecting: human verification in browser
next_action: archive

## Symptoms

expected:
1. /login shows warm off-white (#FAF7F2) and terracotta (#C84B31) — Option D palette
2. Logged-in user navigating to /login is redirected away (to /)
3. Password reset link is single-use — second attempt returns error
4. Logout button exists in UI; clicking it clears session and redirects to /login

actual:
1. /login shows default colors — no terracotta, no warm off-white
2. Logged-in user sees the login card on /login
3. Same reset link can be used multiple times (DISPUTED — backend code is correct)
4. No logout button in the UI

errors: none reported
reproduction: manual browser testing
started: Phase 1 just completed, never tested before

## Eliminated

- hypothesis: main.css not included in nuxt config
  evidence: nuxt.config.ts has css: ['~/assets/css/main.css']
  timestamp: 2026-05-16T00:01:00Z

- hypothesis: consumed_at column missing from DB schema
  evidence: V3__create_token_tables.sql has consumed_at TIMESTAMPTZ in password_reset_tokens
  timestamp: 2026-05-16T00:01:00Z

- hypothesis: resetPassword service doesn't save after marking consumed
  evidence: AuthService.resetPassword() explicitly calls passwordResetTokenRepository.save(token) after setConsumedAt
  timestamp: 2026-05-16T00:01:00Z

## Evidence

- timestamp: 2026-05-16T00:01:00Z
  checked: frontend/assets/css/main.css
  found: CSS variables use raw hex values (#FAF7F2, #C84B31) for --background and --primary
  implication: Tailwind config wraps these with hsl(var(--background)) — hsl(#FAF7F2) is invalid CSS; browser ignores it. Colors don't render.

- timestamp: 2026-05-16T00:01:00Z
  checked: frontend/middleware/auth.global.ts
  found: Middleware returns early for all publicRoutes including /login — no check whether user IS authenticated on public routes
  implication: Authenticated users can freely visit /login — no redirect away

- timestamp: 2026-05-16T00:01:00Z
  checked: AuthService.resetPassword() + PasswordResetToken entity + V3 migration
  found: Backend IS correctly marking tokens as consumed; DB schema has consumed_at column; service checks isConsumed() after lookup
  implication: Issue 3 backend is correct — UAT false positive. Backend unchanged.

- timestamp: 2026-05-16T00:01:00Z
  checked: frontend/layouts/default.vue, frontend/composables/useAuth.ts, AuthController.java
  found: default.vue has NO navbar/header component. useAuth.ts has logout() function. Backend has POST /auth/logout endpoint.
  implication: Logout function exists but no UI element exposes it. Need to add navbar with logout button to default.vue.

## Resolution

root_cause: |
  Issue 1: CSS variables use raw hex (#FAF7F2, #C84B31) but Tailwind renders them as hsl(var(--x)) — invalid CSS, no color applied.
  Issue 2: Auth middleware allows all publicRoutes unconditionally — no reverse guard redirecting authenticated users away from /login.
  Issue 3: Backend correctly implements single-use (UAT observation was erroneous — no code change needed).
  Issue 4: No navbar/header component in default layout — logout() composable exists but has no UI entry point.
fix: |
  Issue 1: Converted CSS variables to HSL component format (space-separated, no hsl() wrapper) matching what Tailwind expects.
  Issue 2: Added isAuthenticated check in auth.global.ts for public auth routes — redirects to / on client-side navigation.
  Issue 4: Added AppNav.vue component with user email + Sign out button; mounted in default.vue only when isAuthenticated.
verification: 56 unit tests pass; commit 364d19e
files_changed:
  - frontend/assets/css/main.css
  - frontend/middleware/auth.global.ts
  - frontend/layouts/default.vue
  - frontend/components/AppNav.vue
  - frontend/test/unit/middleware/auth.spec.ts
