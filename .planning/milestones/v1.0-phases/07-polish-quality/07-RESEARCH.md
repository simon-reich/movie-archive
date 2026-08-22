# Phase 7: Polish & Quality - Research

**Researched:** 2026-05-20
**Domain:** Mobile responsiveness (TailwindCSS), Playwright E2E with Docker Compose, Spring Boot test-only controller, README updates
**Confidence:** HIGH

## Summary

Phase 7 has three independent tracks: (1) mobile responsiveness for AppNav, detail page, and search filter panel using TailwindCSS responsive prefixes, (2) Playwright E2E happy-path tests against the full Docker Compose stack with a test-only Spring Boot seed endpoint, and (3) minimal README additions for new ENV vars and E2E test instructions.

All three tracks are low-risk because the codebase already has the correct foundations in place: Playwright is configured with Desktop Chrome + Mobile Chrome (Pixel 5) projects, `FilterPanel.vue` already uses a `CollapsibleRoot` pattern that collapses well on small screens, the detail page uses a `grid grid-cols-3` that needs a `md:` breakpoint guard, and the Spring Boot security chain pattern is understood. No new libraries need to be added — every tool needed is already installed.

**Primary recommendation:** Implement all three tracks in wave-ordered plans. Start with mobile nav (AppNav hamburger + drawer), then detail page stacking, then filter panel mobile pass, then the Spring Boot test endpoint, then Playwright E2E spec, then CI job, then README.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** On mobile screens, AppNav collapses to a hamburger icon. Tapping it opens a full-height slide-in drawer from the right edge of the screen.
- **D-02:** Drawer appearance: solid warm off-white background (matches page background). NO dark backdrop, no overlay dimming, no translucency of any kind. Strictly editorial — the drawer is a solid white panel that slides in. Claude decides exact width, close button placement, and link spacing within the drawer, following the editorial aesthetic (no rounded corners, terracotta accent for active state).
- **D-03:** Desktop nav remains unchanged — the hamburger icon is hidden on `md:` and above breakpoints.
- **D-04:** All pages must be fully usable on a Pixel 5 viewport (393×851px) — the device already defined in `playwright.config.ts`. QLTY-01 passes when every core flow works at this breakpoint.
- **D-05:** The detail page's two-column layout (hero + main + sidebar) must reflow for mobile: hero stacks, sidebar content (personal fields, trailer) appears below the main content column. Claude decides exact stacking order and spacing.
- **D-06:** Search page filter panel: Claude decides whether filters stack below search bar or collapse into an expandable drawer on mobile. Usable outcome is the requirement.
- **D-07:** E2E tests run against the full Docker stack (`docker compose up`) — real Spring Boot backend, PostgreSQL, OpenSearch, Caddy proxy, and Mailpit. No MSW or mocking in E2E tests.
- **D-08:** E2E tests run both locally (developer runs stack manually) and in CI (GitHub Actions job that starts `docker compose up -d`, waits for health, then runs `playwright test`). The existing CI pipeline gains a new `e2e` job.
- **D-09:** `playwright.config.ts` test projects: Desktop Chrome + Mobile Chrome (Pixel 5) — both already defined. Both must pass.
- **D-10:** A test-only Spring Boot endpoint (e.g., `POST /test/setup`) creates a fully verified user and seeds their TMDB API key directly into the database. This endpoint is **only available when `APP_ENV=test`** (or similar ENV flag) — disabled in production builds.
- **D-11:** The TMDB API key is sourced from a `TEST_TMDB_KEY` environment variable (set in `.env.test` or CI secrets). The setup endpoint reads this from config and inserts it AES-256-GCM encrypted the same way the settings flow would.
- **D-12:** Playwright `beforeAll` calls the setup endpoint to prepare the test user, then logs in via the standard login page (or programmatic login call) to get a session. This exercises the real login flow while keeping test setup fast and deterministic.
- **D-13:** The happy path E2E test covers: navigate to sign-up (already seeded — skip registration) → log in → add a film (search TMDB, save) → wait for enrichment status → open search, find the film → open detail page → verify key fields visible. Covers QLTY-02.
- **D-14:** Test isolation: each E2E run cleans its test user via the setup endpoint (delete + recreate) so tests are idempotent and can run multiple times against the same stack.
- **D-15:** README stays minimal. Add only: missing ENV variable documentation (any new ENV vars introduced in Phase 7, like `APP_ENV`, `TEST_TMDB_KEY`) and a brief section on running E2E tests (`pnpm test:e2e` with prerequisites). Do not add architecture docs, API overview, or contributing guide.

### Claude's Discretion
- Exact hamburger icon and close button implementation (lucide-vue-next `Menu` / `X` icons)
- Drawer width on mobile (full-width or partial — whichever fits the editorial aesthetic)
- Stacking order for the detail page mobile layout (hero → main column → personal fields → trailer, or another sensible order)
- Filter panel mobile behavior on search page (stack or collapse)
- E2E test file structure within `test/e2e/` (single file vs split by flow)
- Exact Spring Boot test endpoint path and ENV flag name

### Deferred Ideas (OUT OF SCOPE)
- Average personal rating + watched/unwatched count on dashboard
- Dark mode
- PWA / offline mode
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| QLTY-01 | App ist responsive und auf Mobilgeräten vollständig nutzbar | AppNav mobile hamburger + drawer (D-01–D-03); detail page `grid-cols-3` → `grid-cols-1 md:grid-cols-3` reflow (D-05); FilterPanel already uses CollapsibleRoot — works on mobile as-is (D-06) |
| QLTY-02 | E2E-Tests mit Playwright (Happy Paths: Sign-Up → Film speichern → suchen → Detail) | Test-only `TestSetupController` with `@Profile("test")`; Playwright `beforeAll` seed + login; happy path spec in `test/e2e/`; CI `e2e` job (D-07–D-14) |
| QLTY-03 | README mit Setup-Anleitung und Feature-Übersicht | README already has setup instructions; Phase 7 adds `APP_ENV`, `TEST_TMDB_KEY` to `.env.example` and a "Running E2E Tests" section (D-15) |
</phase_requirements>

---

## Standard Stack

### Core (all already installed — no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| TailwindCSS | 3.x (via Nuxt) | Responsive breakpoints (`sm:`, `md:`, `lg:`) | Already used throughout the project for grid/flex layouts |
| Playwright | 1.60.0 | E2E browser testing — Desktop Chrome + Mobile Chrome | Already installed; `pnpm test:e2e` script exists; projects configured |
| lucide-vue-next | 0.487.x | Icon library | Already installed; `Menu` + `X` icons for hamburger/close |
| radix-vue CollapsibleRoot | 1.9.x | Accessible open/close primitive | Already used in `FilterPanel.vue` |
| Spring Boot `@Profile` | 3.5.0 | Conditionally register test-only beans/controllers | Standard Spring Boot mechanism; zero new dependencies |

[VERIFIED: codebase grep — playwright.config.ts, package.json scripts, FilterPanel.vue, AppNav.vue, SecurityConfig.java]

### No New Dependencies Required

Phase 7 introduces no new npm packages or Gradle dependencies. All building blocks are present:
- `pnpm test:e2e` → `playwright test` [VERIFIED: package.json scripts]
- `devices['Pixel 5']` already in `playwright.config.ts` [VERIFIED: file read]
- `EncryptionService` injectable for seeding encrypted API keys [VERIFIED: EncryptionService.java]

---

## Architecture Patterns

### Pattern 1: TailwindCSS Mobile-First Responsive Grid

**What:** Swap `grid-cols-3` for `grid-cols-1 md:grid-cols-3` on the detail page body. On mobile (below `md` = 768px), the three-column grid collapses to a single column. Sidebar content naturally flows below main content.

**Current code in `movies/[id].vue`:**
```html
<!-- Line 179 — CURRENT (desktop only) -->
<div class="max-w-7xl mx-auto px-4 py-8 grid grid-cols-3 gap-8">
  <div class="col-span-2 ...">   <!-- main column -->
  <div class="col-span-1 ...">   <!-- sidebar: trailer + personal fields -->
</div>
```

**After mobile fix:**
```html
<div class="max-w-7xl mx-auto px-4 py-8 grid grid-cols-1 md:grid-cols-3 gap-8">
  <div class="col-span-1 md:col-span-2 ...">
  <div class="col-span-1 ...">
</div>
```

On mobile, `col-span-1` / `md:col-span-2` both resolve to full width — sidebar stacks below main column automatically.
[VERIFIED: codebase — movies/[id].vue line 179–324]

**Cast & Crew columns fix:** The `columns-3` CSS multi-column at line 341 also needs `columns-1 md:columns-3` to avoid tiny columns on mobile.
[VERIFIED: codebase — movies/[id].vue line 341]

### Pattern 2: AppNav Mobile Hamburger + Slide-In Drawer

**What:** The existing `AppNav.vue` has a desktop-only nav row. Mobile adds a hamburger button (hidden on `md:` and above) that toggles a full-height slide-in drawer from the right.

**Drawer implementation strategy — CSS transition (no new library):**

The `shadcn-vue Sheet` component is available if needed [ASSUMED — ui/ directory was empty in ls output, Sheet may not be initialized], but a simpler approach that matches the editorial aesthetic uses a `translate-x` CSS transition directly:

```vue
<script setup lang="ts">
const drawerOpen = ref(false)
</script>

<template>
  <!-- Hamburger: visible only below md -->
  <button class="md:hidden" @click="drawerOpen = true">
    <Menu class="w-5 h-5" />
  </button>

  <!-- Desktop nav links: hidden below md -->
  <div class="hidden md:flex items-center gap-4">
    <!-- existing links -->
  </div>

  <!-- Slide-in drawer: full-height, right edge, solid bg-background -->
  <div
    :class="['fixed top-0 right-0 h-full w-64 bg-background z-50 transform transition-transform duration-300',
             drawerOpen ? 'translate-x-0' : 'translate-x-full']"
  >
    <button class="absolute top-4 right-4" @click="drawerOpen = false">
      <X class="w-5 h-5" />
    </button>
    <!-- nav links with generous vertical spacing -->
  </div>
</template>
```

**Key constraints from D-02:** NO backdrop overlay, NO rounded corners, solid `bg-background` (warm off-white). Width `w-64` (256px) leaves a visible edge of the page content — matches the editorial aesthetic better than full-width. Active link: `text-primary` (terracotta).

[VERIFIED: codebase — AppNav.vue; CITED: UI-SPEC.md — no rounded corners, terracotta accent]

### Pattern 3: FilterPanel Already Collapses on Mobile

**What:** `FilterPanel.vue` already uses `CollapsibleRoot` from radix-vue with a trigger button. The content grid uses `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3` — this already works correctly at 393px (Pixel 5 width). The collapsed trigger is touch-friendly.

**Decision for D-06:** The existing collapsible pattern is sufficient. No new "filter drawer" needed. The only improvement needed is ensuring the trigger button height is touch-friendly (≥44px) and the grid inside the expanded panel doesn't overflow on narrow viewports.

[VERIFIED: codebase — FilterPanel.vue lines 108–337; existing grid-cols-1 at line 120]

### Pattern 4: Spring Boot Test-Only Controller with @Profile

**What:** A `TestSetupController` annotated with `@Profile("test")` is registered only when `spring.profiles.active=test`. It exposes `POST /test/setup` which:
1. Deletes the test user if they exist (cascade deletes their movies + API keys)
2. Creates a new `ACTIVE` User (bypassing the email verification flow)
3. Inserts an AES-256-GCM-encrypted TMDB API key using `EncryptionService`
4. Returns `{ "email": "...", "password": "..." }`

**Why `@Profile("test")` is correct:** The Spring Boot `@Profile` annotation on a `@RestController` prevents the bean from being instantiated outside the test profile. When `APP_ENV=test` is absent, the endpoint simply does not exist — returns 404.

**Spring profile activation via ENV:** Set `SPRING_PROFILES_ACTIVE=test` as a Docker Compose ENV variable on the `backend` service. This is the standard Spring Boot way to activate profiles via environment variables.

```java
@RestController
@Profile("test")
@RequestMapping("/test")
public class TestSetupController {

    private final UserRepository userRepository;
    private final UserApiKeyRepository apiKeyRepository;
    private final EncryptionService encryptionService;
    private final BCryptPasswordEncoder passwordEncoder;

    // ... constructor injection

    @PostMapping("/setup")
    @Transactional
    public ResponseEntity<TestSetupResponse> setup(
            @Value("${test.user.email}") String email,
            @Value("${test.user.password}") String password,
            @Value("${test.tmdb.key}") String tmdbKey) {

        // 1. Clean: delete existing test user
        userRepository.findByEmail(email).ifPresent(u -> {
            apiKeyRepository.deleteByUserId(u.getId());
            userRepository.delete(u);
        });

        // 2. Create ACTIVE user
        User user = new User(email, passwordEncoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // 3. Seed encrypted TMDB key using existing EncryptionService
        UserApiKey key = new UserApiKey(user, "TMDB", encryptionService.encrypt(tmdbKey));
        apiKeyRepository.save(key);

        return ResponseEntity.ok(new TestSetupResponse(email, password));
    }
}
```

**application-test.properties (new file):**
```properties
test.user.email=${TEST_USER_EMAIL:e2e@moviearchive.test}
test.user.password=${TEST_USER_PASSWORD:E2ePassword1!}
test.tmdb.key=${TEST_TMDB_KEY:}
```

**Security config update needed:** `/test/**` must be permitted in `SecurityConfig.java`:
```java
.requestMatchers("/auth/**", "/actuator/health", "/settings/confirm-email", "/test/**").permitAll()
```
Note: The `/test/**` permit is safe because the controller bean only exists when `@Profile("test")` is active.

[VERIFIED: codebase — SecurityConfig.java, EncryptionService.java, User.java, UserRepository.java, UserApiKeyRepository.java; CITED: Spring Boot docs @Profile]

### Pattern 5: Playwright E2E Happy Path

**What:** A new `test/e2e/happy-path.spec.ts` alongside the existing `smoke.spec.ts`. Uses `test.beforeAll` to call the setup endpoint and logs in.

**E2E test flow (D-13):**
```typescript
import { test, expect } from '@playwright/test'

const SETUP_URL = process.env.BACKEND_URL || 'http://localhost:8080'

test.describe('Happy path', () => {
  test.beforeAll(async ({ request }) => {
    // Seed test user + TMDB key
    await request.post(`${SETUP_URL}/test/setup`)
  })

  test('login → add film → search → detail', async ({ page }) => {
    // 1. Login
    await page.goto('/login')
    await page.fill('[name=email]', 'e2e@moviearchive.test')
    await page.fill('[name=password]', 'E2ePassword1!')
    await page.click('[type=submit]')
    await expect(page).toHaveURL('/')

    // 2. Add film
    await page.goto('/add')
    await page.fill('[placeholder*=search]', 'Inception')
    await page.click('[data-testid=search-btn]')   // or first result card
    // ... click save, wait for status to show success

    // 3. Search
    await page.goto('/search')
    await page.fill('[placeholder*=search]', 'Inception')
    await expect(page.locator('[data-testid=movie-card]').first()).toBeVisible()

    // 4. Open detail
    await page.locator('[data-testid=movie-card]').first().click()
    await expect(page.locator('h1')).toContainText('Inception')
  })
})
```

**Important:** `data-testid` attributes need to be added to key interactive elements during implementation (add film button, search result cards, status indicator). This is standard Playwright practice.

[VERIFIED: codebase — smoke.spec.ts, playwright.config.ts]

### Pattern 6: GitHub Actions E2E Job

**What:** A new `e2e-ci.yml` (or new job in an existing workflow) that starts the full Docker Compose stack, waits for health checks, runs Playwright, and uploads the HTML report.

```yaml
name: E2E Tests

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  e2e:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up pnpm
        uses: pnpm/action-setup@v4
        with:
          version: 10

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'pnpm'
          cache-dependency-path: frontend/pnpm-lock.yaml

      - name: Install frontend dependencies
        working-directory: frontend
        run: pnpm install --frozen-lockfile

      - name: Install Playwright browsers
        working-directory: frontend
        run: pnpm exec playwright install --with-deps chromium

      - name: Start full Docker Compose stack
        env:
          DB_PASSWORD: e2e-test-password
          JWT_SECRET: e2e-jwt-secret-32-chars-minimum!!
          ENCRYPTION_MASTER_KEY: e2e-master-key-32-characters!!
          SPRING_PROFILES_ACTIVE: test
          TEST_TMDB_KEY: ${{ secrets.TEST_TMDB_KEY }}
        run: docker compose --profile app up -d

      - name: Wait for stack health
        run: |
          echo "Waiting for backend health..."
          for i in $(seq 1 30); do
            if curl -sf http://localhost/api/actuator/health > /dev/null 2>&1; then
              echo "Backend healthy"
              break
            fi
            echo "Attempt $i/30 — waiting 10s..."
            sleep 10
          done

      - name: Run Playwright E2E tests
        working-directory: frontend
        env:
          BASE_URL: http://localhost
          TEST_TMDB_KEY: ${{ secrets.TEST_TMDB_KEY }}
        run: pnpm test:e2e

      - name: Upload Playwright report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: frontend/playwright-report/
          retention-days: 7

      - name: Stop Docker Compose
        if: always()
        run: docker compose --profile app down -v
```

**Key notes:**
- `--with-deps chromium` installs only Chromium (covers both Desktop Chrome and Mobile Chrome projects) — avoids downloading Firefox/WebKit unnecessarily
- `docker compose --profile app` is required to start `backend`, `frontend`, and `caddy` services
- The `SPRING_PROFILES_ACTIVE=test` ENV activates `@Profile("test")` on the backend container
- Health check polls via Caddy proxy (`http://localhost/api/actuator/health`) — same path the frontend uses
- `down -v` cleans volumes so each CI run starts fresh

[VERIFIED: codebase — docker-compose.yml profiles, .github/workflows/backend-ci.yml pattern, playwright.config.ts baseURL]

### Anti-Patterns to Avoid

- **Importing shadcn-vue Sheet for the drawer without checking if it's initialized:** The `ui/` directory was empty — Sheet may not be added to the project. Use a plain CSS `translate-x` transition instead (no shadcn dependency).
- **Activating test profile via `APP_ENV` property only:** Spring Boot reads `SPRING_PROFILES_ACTIVE` as the canonical env var for profile activation. Use this, not a custom `APP_ENV` check in an `@ConditionalOnProperty`.
- **Running `docker compose up` without `--profile app`:** Without the profile, `backend`, `frontend`, and `caddy` services are not started. The E2E test would hit a non-existent server.
- **Applying `@Transactional` across separate repositories without cascade config:** Ensure user deletion cascades properly or delete API keys before the user entity.
- **`test.beforeAll` with `request` fixture runs in a separate browser context:** The `request` fixture in `beforeAll` for a `describe` block needs the `request` parameter from the `test` function, not from `page`. Use `test.beforeAll(async ({ request }) => {...})`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Slide-in drawer transition | Custom JS animation | CSS `transform: translateX` + `transition-transform` | Native CSS handles GPU-accelerated slide with zero JS overhead |
| Mobile breakpoints | JS `window.resize` listener | TailwindCSS `md:` prefix | Already in every other page; consistent with project convention |
| Waiting for Docker health in CI | Custom sleep loop with arbitrary timeout | `docker compose` healthchecks + curl polling loop | Healthchecks are already defined in docker-compose.yml for all services |
| Test user creation logic | Custom DB seeding script | Spring Boot `@Profile("test")` `@RestController` | Reuses `EncryptionService`, `UserRepository`, `BCryptPasswordEncoder` — already wired in the app context |

---

## Common Pitfalls

### Pitfall 1: Detail Page Hero Height on Mobile

**What goes wrong:** The hero div is `h-72` (288px) with `flex items-end` — on Pixel 5 (393px wide), the poster image at `w-32` plus title text wraps poorly in 261px of remaining space. The hero content (`flex items-end gap-6`) needs `flex-wrap` or reduced poster width on mobile.

**Why it happens:** The hero was designed for 1280px desktop. At 393px, the `gap-6` + `w-32` poster consumes ~60% of width, leaving ~150px for the title block.

**How to avoid:** Add `flex-col sm:flex-row` to the hero content row, or reduce poster to `w-20 sm:w-32` on mobile. Also the Delete button in the hero needs to be repositioned — it's currently `justify-between` in the hero row, which may overflow the mobile viewport.

**Warning signs:** Title text clipped or overlapping the poster in Playwright Mobile Chrome screenshot.

[VERIFIED: codebase — movies/[id].vue lines 133–175]

### Pitfall 2: E2E Test — Enrichment Async Timing

**What goes wrong:** After saving a film, the enrichment pipeline runs asynchronously (TMDB → OMDB → Wikipedia → Postgres → OpenSearch). If the test immediately navigates to search and looks for the film, it may not appear yet.

**Why it happens:** The `POST /movies/save` returns 202 Accepted. The actual film data is written to OpenSearch after the async pipeline completes. On a real stack, this takes 1–5 seconds.

**How to avoid:** Use Playwright's `expect(locator).toBeVisible()` with a sufficient timeout (default is 5s; increase to 30s for the enrichment wait). Alternatively, poll the save status endpoint until `status: INDEXED`. The save status UI (`SAVE-05`) shows pending → success — poll for that UI element.

**Warning signs:** Film not found in search results; OpenSearch returns 0 hits.

[VERIFIED: codebase — architecture pattern in CLAUDE.md; save flow returns 202]

### Pitfall 3: `@Profile("test")` Controller Not Reachable Behind JWT Filter

**What goes wrong:** The `SecurityConfig` requires authentication for all requests except the listed permit patterns. `POST /test/setup` will return 401 if not added to `permitAll`.

**Why it happens:** `anyRequest().authenticated()` is a catch-all — `@Profile("test")` does not exempt from security rules.

**How to avoid:** Add `/test/**` to the `permitAll` list in `SecurityConfig.java`. This is safe because the controller bean simply does not exist in production (profile is not active).

[VERIFIED: codebase — SecurityConfig.java line 30]

### Pitfall 4: Playwright `beforeAll` Runs Once Per Worker, Not Per Project

**What goes wrong:** With `fullyParallel: true` and two projects (Desktop Chrome + Mobile Chrome), `beforeAll` may run twice, causing a race condition on the test user setup/teardown.

**Why it happens:** `fullyParallel: true` in `playwright.config.ts` means each project's workers can run concurrently.

**How to avoid:** Either (a) set `workers: 1` for the E2E suite, or (b) make the setup endpoint idempotent (which D-14 already specifies via delete + recreate). Since D-14 requires idempotent setup, option (b) is already handled. But concurrent delete + recreate in parallel can still race. Simplest fix: set `fullyParallel: false` in the E2E test file via `test.describe.configure({ mode: 'serial' })`.

[VERIFIED: codebase — playwright.config.ts line 4 `fullyParallel: true`]

### Pitfall 5: Docker `SPRING_PROFILES_ACTIVE` ENV in docker-compose.yml

**What goes wrong:** The `backend` service in `docker-compose.yml` does not currently pass `SPRING_PROFILES_ACTIVE`. If the CI job sets `SPRING_PROFILES_ACTIVE=test` as a shell env var but doesn't pass it through Compose, the backend container does not activate the test profile.

**Why it happens:** Docker Compose only passes env vars to containers if they are listed in the `environment:` block of the service definition.

**How to avoid:** Add `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}` to the `backend` service's `environment:` block in `docker-compose.yml`. This passes the CI env var through to the container while defaulting to empty string (no profile) in normal use.

[VERIFIED: codebase — docker-compose.yml backend service lines ~73-89]

### Pitfall 6: Cast & Crew Multi-Column Layout on Mobile

**What goes wrong:** `columns-3` at the bottom of the detail page renders three columns at Pixel 5 width (393px), producing ~120px columns — too narrow for actor names.

**How to avoid:** Change to `columns-1 md:columns-3`.

[VERIFIED: codebase — movies/[id].vue line 341]

---

## Code Examples

### Mobile Nav Toggle (AppNav.vue additions)
```vue
<!-- Source: TailwindCSS docs + codebase AppNav.vue pattern -->
<script setup lang="ts">
import { Menu, X, Search, Settings, LogOut } from 'lucide-vue-next'
// ... existing imports

const drawerOpen = ref(false)
</script>

<template>
  <nav class="fixed top-0 left-0 right-0 z-50">
    <div class="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
      <!-- Logo -->
      <NuxtLink ...>MovieArchive</NuxtLink>

      <!-- Desktop links (hidden below md) -->
      <div class="hidden md:flex items-center gap-4">
        <!-- existing links unchanged -->
      </div>

      <!-- Mobile hamburger (hidden above md) -->
      <button class="md:hidden" :class="textClass" @click="drawerOpen = true">
        <Menu class="w-5 h-5" />
      </button>
    </div>
  </nav>

  <!-- Slide-in drawer -->
  <Transition name="slide">
    <div
      v-if="drawerOpen"
      class="fixed top-0 right-0 h-full w-64 bg-background border-l border-border z-[60] flex flex-col pt-16 px-6 gap-6 md:hidden"
    >
      <button class="absolute top-4 right-4 text-foreground" @click="drawerOpen = false">
        <X class="w-5 h-5" />
      </button>
      <!-- Links with generous spacing, uppercase tracking-widest -->
      <NuxtLink
        to="/add"
        class="text-sm font-medium tracking-widest uppercase text-foreground hover:text-primary"
        @click="drawerOpen = false"
      >Add Film</NuxtLink>
      <!-- ... other links -->
    </div>
  </Transition>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.3s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
</style>
```

### Spring Boot Test Controller
```java
// Source: Spring Boot docs — @Profile annotation
@RestController
@Profile("test")
@RequestMapping("/test")
@RequiredArgsConstructor
@Slf4j
public class TestSetupController {

    private final UserRepository userRepository;
    private final UserApiKeyRepository apiKeyRepository;
    private final EncryptionService encryptionService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${test.user.email:e2e@moviearchive.test}")
    private String testEmail;

    @Value("${test.user.password:E2ePassword1!}")
    private String testPassword;

    @Value("${test.tmdb.key:}")
    private String testTmdbKey;

    @PostMapping("/setup")
    @Transactional
    public ResponseEntity<Map<String, String>> setup() {
        // Delete existing test user + their data
        userRepository.findByEmail(testEmail).ifPresent(u -> {
            apiKeyRepository.deleteByUserId(u.getId());
            userRepository.delete(u);
        });

        // Create verified user directly (skip email flow)
        User user = new User(testEmail, passwordEncoder.encode(testPassword));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // Seed encrypted TMDB key
        if (!testTmdbKey.isBlank()) {
            UserApiKey key = new UserApiKey();
            key.setUser(user);
            key.setKeyType("TMDB");
            key.setEncryptedValue(encryptionService.encrypt(testTmdbKey));
            apiKeyRepository.save(key);
        }

        log.info("Test user setup complete: {}", testEmail);
        return ResponseEntity.ok(Map.of("email", testEmail, "password", testPassword));
    }
}
```

---

## Runtime State Inventory

Step 2.5: NOT applicable. Phase 7 is not a rename/refactor/migration phase.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker + Docker Compose | E2E stack (D-07) | ✓ | 29.4.0 | — |
| Playwright | E2E tests | ✓ | 1.60.0 | — |
| pnpm | Frontend build + test:e2e | ✓ | 10.26.2 | — |
| Node.js | Frontend + Playwright | ✓ | v26.0.0 | — |
| Chromium browsers | Playwright Desktop + Mobile Chrome | Needs `playwright install` | bundled with 1.60.0 | `pnpm exec playwright install --with-deps chromium` |

[VERIFIED: bash — docker --version, pnpm --version, node --version, playwright --version]

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:** Playwright browsers may not be installed locally — `pnpm exec playwright install chromium` downloads them on first run. CI job must include the install step.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Playwright 1.60.0 |
| Config file | `frontend/playwright.config.ts` |
| Quick run command | `cd frontend && pnpm test:e2e --project=chromium --grep "Happy path"` |
| Full suite command | `cd frontend && pnpm test:e2e` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| QLTY-01 | Mobile usability on Pixel 5 for all core flows | E2E (Mobile Chrome project) | `cd frontend && pnpm test:e2e --project="Mobile Chrome"` | ❌ Wave 0 — happy-path.spec.ts |
| QLTY-02 | Happy path: login → save film → search → view detail | E2E (both projects) | `cd frontend && pnpm test:e2e` | ❌ Wave 0 — happy-path.spec.ts |
| QLTY-03 | README has E2E section + new ENV vars documented | Manual review | N/A — manual | ❌ Wave 0 — README edits |

### Sampling Rate
- **Per task commit:** `cd frontend && pnpm test:e2e --project=chromium --grep "Happy path"` (Desktop Chrome only, fast feedback)
- **Per wave merge:** `cd frontend && pnpm test:e2e` (both projects)
- **Phase gate:** Full suite green (both projects) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `frontend/test/e2e/happy-path.spec.ts` — covers QLTY-01 (Mobile Chrome) + QLTY-02 (both projects)
- [ ] `backend/src/main/java/de/moviearchive/controller/TestSetupController.java` — prerequisite for E2E test setup
- [ ] `backend/src/main/resources/application-test.properties` — test profile config
- [ ] `data-testid` attributes on: film search result cards (`/add`), save status indicator, search result cards (`/search`), detail page title

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Login flow unchanged |
| V3 Session Management | no | Cookie/JWT unchanged |
| V4 Access Control | yes (test endpoint) | `@Profile("test")` bean gating + `permitAll("/test/**")` only when profile active |
| V5 Input Validation | no | Test endpoint reads from config, not user input |
| V6 Cryptography | no | EncryptionService already implemented |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Test setup endpoint exposed in production | Elevation of privilege | `@Profile("test")` — bean does not exist unless `SPRING_PROFILES_ACTIVE=test` is set |
| Test user creation races in parallel CI | Denial of service (test reliability) | Idempotent delete + recreate (D-14); `test.describe.configure({ mode: 'serial' })` |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | shadcn-vue `Sheet` component is NOT initialized in the project (ui/ directory was empty) | Architecture Patterns #2 | If Sheet IS available, it could replace the manual CSS transition — lower implementation effort. Risk is low: manual CSS transition works regardless. |
| A2 | `UserApiKeyRepository.deleteByUserId(UUID)` exists or can be derived | Architecture Patterns #4 | If no delete-by-userId method exists, the seed controller needs `apiKeyRepository.findAll().stream().filter(...)` or a custom JPQL query. Medium risk — easy to add. |
| A3 | The `UserApiKey` entity accepts a `keyType` string field named exactly `keyType` | Architecture Patterns #4 | Field name may differ. Verify against `UserApiKey.java` before implementing. |

---

## Open Questions

1. **Does `UserApiKeyRepository` have a `deleteByUserId` method?**
   - What we know: `UserApiKeyRepository` exists at `de.moviearchive.settings.UserApiKeyRepository`
   - What's unclear: Whether Spring Data method naming or a custom query exists for deleting by userId
   - Recommendation: Implementer should read the file and add `void deleteByUserId(UUID userId)` if absent

2. **What is the exact `UserApiKey` entity structure (field names, key type enum or string)?**
   - What we know: `UserApiKey.java` exists; `EncryptionService.encrypt()` is injectable
   - What's unclear: Whether `keyType` is an enum (`TMDB`, `OMDB`) or a plain String
   - Recommendation: Implementer reads `UserApiKey.java` before coding `TestSetupController`

3. **Does the `/add` page have a TMDB film search that returns results without a pre-configured TMDB key?**
   - What we know: The save flow requires a TMDB key; the test setup endpoint seeds one
   - What's unclear: Whether the add page proactively fetches TMDB or only on submit
   - Recommendation: E2E test flow must verify the TMDB key seed worked before attempting film search

---

## Sources

### Primary (HIGH confidence)
- `frontend/playwright.config.ts` — Playwright project config, baseURL, device list verified
- `frontend/components/AppNav.vue` — Current nav structure; no mobile treatment confirmed
- `frontend/components/FilterPanel.vue` — Existing CollapsibleRoot + grid-cols-1 confirmed
- `frontend/pages/movies/[id].vue` — grid-cols-3 layout, h-72 hero, columns-3 cast section confirmed
- `frontend/pages/search.vue` — Current search page structure confirmed
- `backend/src/main/java/de/moviearchive/config/SecurityConfig.java` — permitAll list verified
- `backend/src/main/java/de/moviearchive/settings/EncryptionService.java` — Injectable encrypt() confirmed
- `backend/src/main/java/de/moviearchive/user/User.java` — User entity + UserStatus confirmed
- `docker-compose.yml` — Service profiles, healthchecks, env var passthrough pattern confirmed
- `.github/workflows/frontend-ci.yml` + `backend-ci.yml` — CI structure confirmed; no e2e job yet
- `frontend/package.json` scripts — `test:e2e: playwright test` confirmed
- `.env.example` — Current ENV vars documented; `APP_ENV`/`TEST_TMDB_KEY` absent confirmed
- `.planning/UI-SPEC.md` — No rounded corners, terracotta accent, editorial aesthetic

### Secondary (MEDIUM confidence)
- Spring Boot `@Profile` annotation behavior — standard Spring Boot feature; activates via `SPRING_PROFILES_ACTIVE` env var
- Playwright `beforeAll` + `request` fixture pattern — standard Playwright API

### Tertiary (LOW confidence)
- None

---

## Metadata

**Confidence breakdown:**
- Mobile responsiveness: HIGH — exact code to change is identified in the codebase
- E2E test structure: HIGH — Playwright config and existing spec confirmed; setup endpoint pattern is standard Spring Boot
- CI job: HIGH — existing CI yml structure is the template; docker-compose healthchecks confirmed
- README: HIGH — current README read; gaps (APP_ENV, TEST_TMDB_KEY, E2E section) confirmed

**Research date:** 2026-05-20
**Valid until:** 2026-06-20 (stable stack — Playwright and Spring Boot versions unlikely to change)
