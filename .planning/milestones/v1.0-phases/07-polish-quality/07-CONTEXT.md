# Phase 7: Polish & Quality - Context

**Gathered:** 2026-05-20
**Status:** Ready for planning

<domain>
## Phase Boundary

All core flows (auth, save, search, detail) are fully usable on mobile devices. Playwright E2E tests cover the sign-up → verify email → save film → search → view detail happy path, running against a full Docker stack both locally and in CI. The README is updated with missing ENV vars and E2E test instructions.

**Scope anchor:** QLTY-01, QLTY-02, QLTY-03

</domain>

<decisions>
## Implementation Decisions

### Mobile Navigation

- **D-01:** On mobile screens, AppNav collapses to a hamburger icon. Tapping it opens a full-height slide-in drawer from the right edge of the screen.
- **D-02:** Drawer appearance: solid warm off-white background (matches page background). NO dark backdrop, no overlay dimming, no translucency of any kind. Strictly editorial — the drawer is a solid white panel that slides in. Claude decides exact width, close button placement, and link spacing within the drawer, following the editorial aesthetic (no rounded corners, terracotta accent for active state).
- **D-03:** Desktop nav remains unchanged — the hamburger icon is hidden on `md:` and above breakpoints.

### Mobile Responsiveness (All Pages)

- **D-04:** All pages must be fully usable on a Pixel 5 viewport (393×851px) — the device already defined in `playwright.config.ts`. QLTY-01 passes when every core flow works at this breakpoint.
- **D-05:** The detail page's two-column layout (hero + main + sidebar) must reflow for mobile: hero stacks, sidebar content (personal fields, trailer) appears below the main content column. Claude decides exact stacking order and spacing.
- **D-06:** Search page filter panel: Claude decides whether filters stack below search bar or collapse into an expandable drawer on mobile. Usable outcome is the requirement.

### E2E Test Environment

- **D-07:** E2E tests run against the full Docker stack (`docker compose up`) — real Spring Boot backend, PostgreSQL, OpenSearch, Caddy proxy, and Mailpit. No MSW or mocking in E2E tests.
- **D-08:** E2E tests run both locally (developer runs stack manually) and in CI (GitHub Actions job that starts `docker compose up -d`, waits for health, then runs `playwright test`). The existing CI pipeline gains a new `e2e` job.
- **D-09:** `playwright.config.ts` test projects: Desktop Chrome + Mobile Chrome (Pixel 5) — both already defined. Both must pass.

### E2E Auth Handling

- **D-10:** A test-only Spring Boot endpoint (e.g., `POST /test/setup`) creates a fully verified user and seeds their TMDB API key directly into the database. This endpoint is **only available when `APP_ENV=test`** (or similar ENV flag) — disabled in production builds.
- **D-11:** The TMDB API key is sourced from a `TEST_TMDB_KEY` environment variable (set in `.env.test` or CI secrets). The setup endpoint reads this from config and inserts it AES-256-GCM encrypted the same way the settings flow would.
- **D-12:** Playwright `beforeAll` calls the setup endpoint to prepare the test user, then logs in via the standard login page (or programmatic login call) to get a session. This exercises the real login flow while keeping test setup fast and deterministic.

### E2E Test Happy Path Scope

- **D-13:** The happy path E2E test covers: navigate to sign-up (already seeded — skip registration) → log in → add a film (search TMDB, save) → wait for enrichment status → open search, find the film → open detail page → verify key fields visible. Covers QLTY-02.
- **D-14:** Test isolation: each E2E run cleans its test user via the setup endpoint (delete + recreate) so tests are idempotent and can run multiple times against the same stack.

### README Scope

- **D-15:** README stays minimal. Add only: missing ENV variable documentation (any new ENV vars introduced in Phase 7, like `APP_ENV`, `TEST_TMDB_KEY`) and a brief section on running E2E tests (`pnpm test:e2e` with prerequisites). Do not add architecture docs, API overview, or contributing guide.

### Claude's Discretion

- Exact hamburger icon and close button implementation (lucide-vue-next `Menu` / `X` icons)
- Drawer width on mobile (full-width or partial — whichever fits the editorial aesthetic)
- Stacking order for the detail page mobile layout (hero → main column → personal fields → trailer, or another sensible order)
- Filter panel mobile behavior on search page (stack or collapse)
- E2E test file structure within `test/e2e/` (single file vs split by flow)
- Exact Spring Boot test endpoint path and ENV flag name

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Design System
- `.planning/UI-SPEC.md` — Global design contract: warm off-white + deep terracotta, NO rounded corners, editorial/avantgardistic aesthetic, shadcn-vue components, lucide-vue-next icons. **All mobile UI work must follow this spec.**

### Test Strategy
- `.claude/test-strategy.md` — Test pyramid, tooling per layer, E2E happy paths, CI pipeline. Read before writing Playwright tests or adding CI jobs.

### Requirements
- `.planning/REQUIREMENTS.md` §QLTY-01, QLTY-02, QLTY-03 — Authoritative requirement text for this phase.

### Prior Phase Context
- `.planning/phases/06-movie-detail-personal-fields/06-CONTEXT.md` — D-02 through D-05 (detail page layout decisions — must know the desktop layout before implementing mobile reflow), D-03 (two-column with sidebar — this is what stacks on mobile).

### Existing Playwright Config
- `frontend/playwright.config.ts` — Already defines Desktop Chrome + Mobile Chrome (Pixel 5). `testDir: ./test/e2e`. `baseURL: http://localhost`.

### Existing Frontend Structure
- `frontend/components/AppNav.vue` — Current nav (no mobile treatment). Phase 7 adds hamburger + drawer here.
- `frontend/pages/search.vue` — Search page. Mobile reflow needed.
- `frontend/pages/movies/[id].vue` — Detail page. Two-column layout needs mobile stacking.

### Auth Patterns
- `CLAUDE.md` §JWT Authentication — Auth endpoint patterns. Test setup endpoint follows same security patterns but is guarded by ENV flag.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/components/AppNav.vue` — Current nav with `overlay` prop. Phase 7 extends it with mobile hamburger state.
- `frontend/playwright.config.ts` — Already configured with Mobile Chrome (Pixel 5) and Desktop Chrome. `test/e2e/` directory and `smoke.spec.ts` exist.
- `frontend/test/e2e/smoke.spec.ts` — Existing smoke test (home page heading check). Phase 7 adds full happy-path spec alongside it.
- shadcn-vue `Sheet` or `Dialog` component — Likely the right primitive for the slide-in drawer (already in the component library).
- lucide-vue-next `Menu` + `X` icons — Use for hamburger open/close toggle.

### Established Patterns
- TailwindCSS responsive prefixes (`sm:`, `md:`, `lg:`) — Already used in `pages/add.vue` and `pages/index.vue` for responsive grids. Same pattern for all Phase 7 responsive work.
- `frontend/test/` structure — `unit/`, `mocks/`, `e2e/`, `setup.ts` already organized. E2E specs go in `test/e2e/`.
- GitHub Actions CI — Existing workflows in `.github/workflows/`. Phase 7 adds an `e2e` job.

### Integration Points
- `frontend/components/AppNav.vue` — Primary target for mobile nav changes.
- `frontend/pages/movies/[id].vue` — Detail page two-column layout needs `md:` breakpoint classes for stacking.
- `frontend/pages/search.vue` — Filter panel needs mobile layout treatment.
- `backend/` — New test-only controller (`TestSetupController`) for E2E user seeding, guarded by `APP_ENV` property.

</code_context>

<specifics>
## Specific Ideas

- **Drawer aesthetic:** White panel, no backdrop overlay, slides from the right. The editorial feel comes from NO rounded corners, generous vertical spacing between links, uppercase tracking-widest on link text (matching existing nav style). This is NOT a translucent sheet — it's a solid opaque white panel.
- **Mobile Playwright project already defined:** `devices['Pixel 5']` is in `playwright.config.ts`. The E2E happy path must pass on this device.
- **Test setup endpoint security:** Only reachable when `spring.profiles.active=test` or `APP_ENV=test`. Spring Boot `@Profile("test")` annotation on the controller is the cleanest guard.

</specifics>

<deferred>
## Deferred Ideas

- Average personal rating + watched/unwatched count on dashboard — meaningful now that Phase 6 writes `personal_rating` and `watched`. Could be added to the dashboard in a future iteration.
- Dark mode — not in scope for v1.
- PWA / offline mode — explicitly out of scope (PROJECT.md).

None of the discussion introduced new capabilities outside Phase 7 scope.

</deferred>

---

*Phase: 07-polish-quality*
*Context gathered: 2026-05-20*
