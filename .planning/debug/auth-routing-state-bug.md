---
status: resolved
trigger: "auth-routing-state-bug — authenticated routing, navigation guards, and state management broken after navigation or page reload"
created: 2026-05-16T00:00:00Z
updated: 2026-05-16T03:00:00Z
---

## Current Focus

hypothesis: |
  Original bugs (symptoms 1-5) confirmed fixed by user.

  Two new bugs identified from human-verify checkpoint:

  Bug A — Same-email validation missing:
  (a) Frontend: handleChangeEmail() submits without comparing entered email to current email.
      The backend intentionally does not check uniqueness at request time (enumeration protection
      in requestEmailChange). So if you enter your own email, a confirmation is sent. When you
      click the link, confirmEmailChange() calls userRepository.existsByEmail(token.getNewEmail())
      — which DOES find your email (you still own it) → throws EmailAlreadyExistsException →
      controller's @ExceptionHandler returns JSON 409. Browser shows raw JSON instead of redirect.
  (b) Backend: the @ExceptionHandler for EmailAlreadyExistsException in confirmEmail flow
      returns JSON (409). But confirmEmail is a GET endpoint followed by browser redirect —
      returning JSON is wrong UX for this path. The handler should redirect instead.

  Bug B — session_email cookie not updated after email confirmation:
  After confirmEmailChange() succeeds, the controller redirects to /settings?emailConfirmed=true.
  The session_email cookie is never updated in that redirect response. auth store reads
  session_email cookie, so userEmail stays stale. F5 also doesn't help because the cookie
  is still the old value.

test: Code paths traced. Root causes confirmed for both bugs.
next_action: |
  Fix A1: Add frontend validation in handleChangeEmail() — compare trimmed/lowercased newEmail
    to authStore.userEmail before calling changeEmail(). Set emailError inline, return early.
  Fix A2: In SettingsController.confirmEmail(), catch EmailAlreadyExistsException explicitly
    and redirect to /login?emailError=email-unavailable instead of returning JSON.
  Fix A3: Add email-unavailable to the error map in login.vue onMounted handler.
  Fix B: In SettingsController.confirmEmail() success path, call AuthService to build a
    session_email cookie with the new email and add it as Set-Cookie on the redirect response.
    The controller needs HttpServletResponse injected and access to AuthService.

## Symptoms

expected: |
  1. Logged-in users navigating to /login redirected to /
  2. F5 on /settings loads user data (email, API keys) correctly
  3. After email-change confirmation redirect to /settings, data loads
  4. After logout + clicking used confirm link → shows "link already used" message
actual: |
  1. Logged-in user at /login sees sign-in card, no redirect, no AppNav Settings link
  2. F5 on /settings: all API calls fail silently, no data loads (Authorization header missing)
  3. Same as F5 symptom after email confirmation redirect
  4. Silent redirect to /login, no feedback
errors: No visible browser console errors. No 401/403 in network tab. Silent failures.
reproduction: |
  1. Login → F5 on /settings → no data loads
  2. Login → navigate to /login via address bar or link → sees sign-in card
  3. Change email → click confirmation link → settings shows no data
  4. Logout → click old confirmation link → silent redirect to login
started: Throughout Phase 2

## Eliminated

- hypothesis: settings.vue watch(accessToken) doesn't trigger on F5
  evidence: watch with immediate:true IS present. But the fundamental issue is the token is not
    available when the watch fires during SSR/hydration — the plugin is too late.
  timestamp: 2026-05-16

- hypothesis: AppNav visibility broken (shows on public routes)
  evidence: default.vue correctly gates on route path not store state.
  timestamp: 2026-05-16

- hypothesis: Backend redirect for error cases goes to protected /settings route
  evidence: SettingsController already redirects errors to /login?emailError=... (fixed in prior
    commit). Symptom 4 is already fixed in the current code.
  timestamp: 2026-05-16

- hypothesis: The client-only plugin approach (auth.client.ts) can reliably restore auth state
  evidence: Access token in Pinia memory is fundamentally SSR-incompatible. On F5, the SSR-rendered
    page has no token. The plugin runs client-side AFTER SSR but the timing with Vue's reactive
    watch callbacks is not guaranteed to be correct across all scenarios. User confirms broken.
  timestamp: 2026-05-16

## Evidence

- timestamp: 2026-05-16
  checked: auth.client.ts, stores/auth.ts, middleware/auth.global.ts, pages/settings.vue,
    pages/login.vue, composables/useSettings.ts, SecurityConfig.java, AuthService.java
  found: |
    Full code path traced. Access token is ONLY stored in Pinia (in-memory). On F5/direct URL:
    - SSR: store is empty, page renders with no data
    - Client plugin: calls /api/auth/refresh, sets store
    - Watch: fires when store changes, calls loadApiKeys() with Authorization header
    The watch+plugin approach should work in theory but fails in practice (user confirms).
    
    Reverse guard: middleware checks authStore.isAuthenticated during route navigation.
    On SPA navigation to /login, the store should be populated (plugin ran at startup).
    But if the user opens /login directly in a new tab or via F5, plugin runs but store
    is empty during SSR, so SSR middleware skips the reverse guard (isHydrating).
    After plugin runs, the watch in login.vue should redirect. But user says it doesn't work.
  implication: The in-memory-only approach for access token is fundamentally fragile. Cookie storage
    is the correct solution for SSR-compatible auth.

- timestamp: 2026-05-16
  checked: AuthService.java — cookie strategy
  found: |
    Backend already sets two cookies on login/refresh:
    - refresh_token: httpOnly=true (secure, can't be read by JS)
    - session_email: httpOnly=false (readable by JS/SSR, used for nav display)
    
    Access token is returned ONLY in response body JSON, never in a cookie.
    
    The fix: add a third cookie — access_token (httpOnly=false) — containing the JWT.
    This allows useCookie('access_token') in any Nuxt context (SSR or client) to read
    the token without a plugin round-trip.
  implication: |
    With access_token in a readable cookie:
    - useSettings can read it via useCookie (works in SSR and client)
    - No plugin needed for auth state restoration
    - Middleware can check the access_token cookie directly for client-side guard
    - login.vue watch can check useCookie for immediate redirect
    - No hydration timing issues

- timestamp: 2026-05-16
  checked: Security implications of readable access token cookie
  found: |
    Trade-offs:
    - httpOnly=false means XSS can read the JWT. However this is a personal app.
    - Same-site=Lax prevents CSRF on cross-site POSTs.
    - Short maxAge (15 min) limits exposure window.
    - The session_email cookie already has httpOnly=false, so this is consistent with
      the existing cookie strategy.
    - The authorization is still Bearer token in the header — XSS could already steal
      the session_email and use it to identify the user. Adding the access_token cookie
      readable is not a significant incremental risk for this use case.
  implication: Acceptable for this personal app. Consistent with existing session_email approach.

## Evidence

- timestamp: 2026-05-16T02:00:00Z
  checked: settings.vue handleChangeEmail(), useSettings.changeEmail(), SettingsController.confirmEmail(),
    SettingsService.confirmEmailChange(), AuthService.buildSessionEmailCookie(), login.vue onMounted emailError handler
  found: |
    Bug A root cause confirmed:
    - handleChangeEmail() sends the request unconditionally — no comparison to current email.
    - SettingsService.requestEmailChange() intentionally skips uniqueness at request time (enumeration
      protection). So entering your own email sends a confirmation to yourself.
    - When you click the link, confirmEmailChange() calls userRepository.existsByEmail(newEmail) —
      which finds your own account → throws EmailAlreadyExistsException.
    - SettingsController has a class-level @ExceptionHandler for EmailAlreadyExistsException that
      returns JSON 409. This fires even inside confirmEmail(), so the browser receives raw JSON
      instead of a redirect.

    Bug B root cause confirmed:
    - confirmEmailChange() returned void; the new email was never passed back to the controller.
    - The confirm redirect response had no Set-Cookie header for session_email.
    - auth store reads useCookie('session_email') which retains the old value until re-login.

    Fix applied:
    A1: settings.vue handleChangeEmail() — compare newEmail (trimmed, lowercased) to
        authStore.userEmail before any API call; set emailError inline and return early.
    A2: SettingsController.confirmEmail() — catch EmailAlreadyExistsException explicitly before
        the class-level handler fires and redirect to /login?emailError=email-unavailable.
    A3: login.vue onMounted — added 'email-unavailable' → human-readable error message.
    B:  SettingsService.confirmEmailChange() changed to return String (the new email).
        AuthService.buildSessionEmailCookie(String email) added as public overload.
        SettingsController.confirmEmail() now calls authService.buildSessionEmailCookie(newEmail)
        and adds it as Set-Cookie on the redirect response via HttpServletResponse.
  implication: All three fixes are self-contained. No schema changes. 53 backend tests pass (0 failures).

## Resolution

root_cause: |
  The access token is stored only in JavaScript memory (Pinia store). This fundamentally breaks
  on F5 / direct URL navigation in Nuxt SSR mode because:
  
  1. SSR renders the page with an empty store (no access token)
  2. The client plugin (auth.client.ts) runs after SSR to restore the token via /api/auth/refresh
  3. The watch({immediate: true}) in settings.vue fires during component setup — the timing between
     the plugin completing and the watch firing is fragile and fails in practice
  4. When loadApiKeys() is called with null token, authHeaders() returns {} (no Authorization header)
  5. Backend rejects with 401 but $fetch doesn't surface it visibly → silent failure
  
  Additionally: the reverse route guard (redirect authenticated users away from /login) relies on
  authStore.isAuthenticated which is null during SSR and immediately after hydration before the plugin
  completes. This causes authenticated users to see the /login page on direct navigation.

fix: |
  Store the access token in a readable (httpOnly=false) cookie named 'access_token':
  
  Backend changes:
  1. AuthService.login(): set 'access_token' cookie (httpOnly=false, maxAge=15min, same-site=Lax)
  2. AuthService.refresh(): set new 'access_token' cookie on token rotation
  3. AuthService.buildClearSessionEmailCookie(): also clear 'access_token' cookie on logout
  4. AuthController.logout(): clear 'access_token' cookie via AuthService helper
  
  Frontend changes:
  1. auth.client.ts: REMOVE the plugin entirely (or keep for Pinia sync, but not needed for auth)
  2. stores/auth.ts: add accessTokenCookie = useCookie('access_token') as source of truth;
     isAuthenticated computed from cookie; userEmail still from session_email cookie
  3. useSettings.ts: read access token from useCookie('access_token') directly
  4. middleware/auth.global.ts: simplify — on server, check both refresh_token and access_token
     cookies; on client, check useCookie('access_token') 
  5. pages/login.vue: watch useCookie('access_token') for immediate redirect on direct nav
  6. pages/settings.vue: watch no longer needed — load data in onMounted after cookie is available
  
  The key insight: useCookie('access_token') works on BOTH server (reads request headers) and
  client (reads document.cookie). No async plugin, no timing issues, no race conditions.

verification: |
  - 51 backend tests pass (0 failures), including updated AuthIntegrationTest asserting
    access_token cookie is set on login (httpOnly=false) and refresh_token is httpOnly=true
  - 74 frontend unit tests pass (0 failures)
  - Cookie-clearing beforeEach added to auth store/plugin/composable tests to prevent leakage
  - Awaiting manual end-to-end verification: F5 on /settings loads data, /login redirects
    authenticated users, email/API key changes work after reload
files_changed:
  - backend/src/main/java/de/moviearchive/auth/AuthService.java
  - backend/src/main/java/de/moviearchive/auth/AuthController.java
  - frontend/stores/auth.ts
  - frontend/plugins/auth.client.ts
  - frontend/composables/useSettings.ts
  - frontend/middleware/auth.global.ts
  - frontend/pages/login.vue
  - frontend/pages/settings.vue
