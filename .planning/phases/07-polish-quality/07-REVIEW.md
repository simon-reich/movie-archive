---
phase: 07-polish-quality
reviewed: 2026-05-20T00:00:00Z
depth: standard
files_reviewed: 11
files_reviewed_list:
  - .env.example
  - .github/workflows/e2e-ci.yml
  - README.md
  - backend/src/main/java/de/moviearchive/config/SecurityConfig.java
  - backend/src/main/java/de/moviearchive/controller/TestSetupController.java
  - backend/src/main/java/de/moviearchive/settings/UserApiKeyRepository.java
  - backend/src/main/resources/application-test.properties
  - docker-compose.yml
  - frontend/components/AppNav.vue
  - frontend/components/MovieCard.vue
  - frontend/pages/add.vue
  - frontend/pages/movies/[id].vue
  - frontend/test/e2e/happy-path.spec.ts
findings:
  critical: 2
  warning: 4
  info: 3
  total: 9
status: issues_found
---

# Phase 7: Code Review Report

**Reviewed:** 2026-05-20
**Depth:** standard
**Files Reviewed:** 11
**Status:** issues_found

Note: Two files from the declared scope were not found in the worktree (`backend/src/test/resources/application-test.properties` differs from the `application-test.properties` reviewed, which is under `src/main/resources`). The file reviewed is the one that exists in the worktree at `backend/src/main/resources/application-test.properties`.

---

## Summary

This phase delivers the E2E test infrastructure, CI pipeline, Docker Compose full-stack wiring, and the README. The code is generally well-structured. Two critical issues stand out: the `TestSetupController` exposes plaintext test credentials in its HTTP response body (a data-exposure risk), and the CI workflow passes secrets as plain environment variables rather than using Docker Compose secrets injection, which means they appear in CI logs when debugging. Four warnings cover missing health-check propagation in the wait loop, a missing `data-testid` attribute that will make E2E tests fail, a hardcoded colour value that bypasses the design token system, and a missing `type` attribute on the `<label>` for the notes textarea. Three info items cover minor documentation and style issues.

---

## Critical Issues

### CR-01: TestSetupController returns plaintext password in response body

**File:** `backend/src/main/java/de/moviearchive/controller/TestSetupController.java:65`
**Issue:** The `/test/setup` endpoint returns `Map.of("email", testEmail, "password", testPassword)`. Even though this controller is gated behind `@Profile("test")`, returning a plaintext password in any HTTP response body is bad practice. The password value ends up in CI logs, Playwright traces, HAR files, and anywhere the response is recorded. Because the test profile is activated in the CI environment (`SPRING_PROFILES_ACTIVE: test`), this is a real exposure vector.
**Fix:**
```java
// Return only the email; the test already knows its own password constant.
return ResponseEntity.ok(Map.of("email", testEmail));
```

### CR-02: Health-check wait loop in CI does not fail the job if the backend never becomes healthy

**File:** `.github/workflows/e2e-ci.yml:49-57`
**Issue:** The wait loop iterates 30 times and prints a warning on each failed attempt, but it never `exit 1` when exhausted. If the backend fails to start, the loop silently completes and the Playwright step runs against an unavailable stack, producing confusing test failures instead of a clear "stack never came up" error.
**Fix:**
```yaml
- name: Wait for backend health
  run: |
    echo "Waiting for backend health via Caddy proxy..."
    for i in $(seq 1 30); do
      if curl -sf http://localhost/api/actuator/health > /dev/null 2>&1; then
        echo "Backend healthy after attempt $i"
        exit 0
      fi
      echo "Attempt $i/30 — waiting 10s..."
      sleep 10
    done
    echo "ERROR: backend did not become healthy after 300s"
    exit 1
```

---

## Warnings

### WR-01: E2E test references `data-testid="poster-card"` and `data-testid="save-status"` but `add.vue` has no such attributes

**File:** `frontend/test/e2e/happy-path.spec.ts:35-39`
**Issue:** The test locates elements with `[data-testid="poster-card"]` and `[data-testid="save-status"]`, but the grid items in `frontend/pages/add.vue` (lines 127-173) have no `data-testid` attributes. These selectors will never match, causing the test to time out at line 35. Similarly, `[data-testid="movie-title"]` (line 52) is absent from `frontend/pages/movies/[id].vue`. The test is unusable as written.
**Fix:** Add the expected attributes to the relevant elements in `add.vue`:
```html
<!-- line 127 — the poster card wrapper -->
<div
  v-for="item in results"
  :key="item.tmdbId"
  data-testid="poster-card"
  class="relative cursor-pointer group overflow-hidden"
  @click="handlePosterClick(item)"
>
  <!-- ... -->
  <!-- the overlay that shows during pending/success/error states -->
  <div
    v-if="item.state !== 'idle' && item.state !== 'saved'"
    data-testid="save-status"
    class="absolute inset-0 ..."
  >
```
And in `movies/[id].vue`:
```html
<h1 data-testid="movie-title" class="text-2xl font-semibold ...">{{ movie.title }}</h1>
```

### WR-02: CI wait loop passes `TEST_TMDB_KEY` secret as a plain `env` var on the `docker compose up` step, not through Docker secrets

**File:** `.github/workflows/e2e-ci.yml:39-45`
**Issue:** `TEST_TMDB_KEY: ${{ secrets.TEST_TMDB_KEY }}` is set as a process environment variable on the `docker compose up -d` step. Docker Compose then reads it from the environment, but it will also appear in `docker inspect`, in the container's `/proc/1/environ`, and potentially in CI debug output. The value is not sensitive to the degree of a production credential, but it is a real API key that has rate limits. The pattern should use Docker Compose's secret injection or pass via a `.env` file written to a temp path.

**Fix (lowest friction):** Inject only what the backend needs via the Compose file's `environment` block rather than inheriting from the shell:
```yaml
# In docker-compose.yml, under backend.environment:
TEST_TMDB_KEY: ${TEST_TMDB_KEY:-}
```
This is already the pattern used; no change needed there. The issue is that the CI step exports the secret into the shell environment, where it is visible to all child processes. A minimal fix:
```yaml
- name: Start full Docker Compose stack
  run: |
    echo "TEST_TMDB_KEY=${{ secrets.TEST_TMDB_KEY }}" >> /tmp/e2e.env
    DB_PASSWORD=e2e-test-db-password \
    JWT_SECRET=e2e-jwt-secret-minimum-32-chars!! \
    ENCRYPTION_MASTER_KEY=e2e-master-key-exactly-32chars! \
    SPRING_PROFILES_ACTIVE=test \
    docker compose --env-file /tmp/e2e.env --profile app up -d
```

### WR-03: Hardcoded hex colour `#7A3520` in movies/[id].vue bypasses the design token system

**File:** `frontend/pages/movies/[id].vue:115`
**Issue:** The error state paragraph uses `text-[#7A3520]` (a raw hex colour via Tailwind's arbitrary-value syntax). All other colours in the codebase use semantic tokens (`text-foreground`, `text-muted-foreground`, `text-primary`). Using a raw hex here means this colour does not respond to theme changes and is invisible to a future dark/light mode toggle.
**Fix:**
```html
<!-- Replace: -->
<p class="text-[#7A3520]">{{ error }}</p>
<!-- With a semantic token — destructive is the conventional choice for errors: -->
<p class="text-destructive">{{ error }}</p>
```

### WR-04: `notesDebounce` timer in `movies/[id].vue` is never cleared on component unmount

**File:** `frontend/pages/movies/[id].vue:49-74`
**Issue:** `notesDebounce` is a module-level `let` that holds a `setTimeout` handle. The `onNotesInput` function clears and resets it, but there is no `onUnmounted` hook to cancel the pending timer when the user navigates away. If the user types in the notes field and immediately navigates, the `updatePersonal` call fires after the component is gone, making a stale request against an already-unmounted context.
**Fix:**
```ts
onUnmounted(() => {
  if (notesDebounce) clearTimeout(notesDebounce)
})
```

---

## Info

### IN-01: README states "Java 21" but CLAUDE.md and build.gradle.kts lock the toolchain to Java 25

**File:** `README.md:129` and `README.md:15`
**Issue:** The README prerequisites section says "Java 21 (for local backend development)" and the project structure comment says `Spring Boot 3 + Java 21`. CLAUDE.md documents the locked toolchain as Java 25. This will confuse new contributors who install Java 21 and then hit a Gradle toolchain resolution error.
**Fix:** Update both references:
```markdown
- Java 25 (for local backend development)
```
```
├── backend/          # Spring Boot 3 + Java 25
```

### IN-02: `docker-compose.yml` exposes OpenSearch on `0.0.0.0:9200` without authentication

**File:** `docker-compose.yml:57`
**Issue:** `DISABLE_SECURITY_PLUGIN: "true"` turns off all OpenSearch authentication. Port 9200 is bound to all interfaces, meaning any process on the host (or on a shared network with port forwarding) can read or modify the search index without credentials. This is intentional for local dev convenience but worth flagging for production awareness. The README does not mention this limitation.
**Fix (info only):** For production use, remove `DISABLE_SECURITY_PLUGIN` and configure proper credentials. For local dev this is acceptable; consider adding a comment:
```yaml
DISABLE_SECURITY_PLUGIN: "true"   # dev only — remove for production
```

### IN-03: `AppNav.vue` avatar button has no accessible label and `w-4 h-4` sizing is likely too small

**File:** `frontend/components/AppNav.vue:52-57`
**Issue:** The avatar button has a `:title` tooltip but no `aria-label`, so screen readers announce "button" with no context. Additionally `w-4 h-4` (16×16 px) is very small for a clickable target — WCAG 2.5.5 recommends at least 44×44 px touch targets. The button currently does nothing on click (no `@click` handler), which may be intentional for a display-only avatar, but if it is meant to open a profile menu it is an incomplete stub.
**Fix:** Add an `aria-label` matching the visible tooltip content:
```html
<button
  :aria-label="`Signed in as ${authStore.userEmail ?? 'unknown'}`"
  :class="['w-8 h-8 rounded-full ...', avatarBg]"
  :title="authStore.userEmail ?? ''"
>
```

---

_Reviewed: 2026-05-20_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
