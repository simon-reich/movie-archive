# Retrospective

## Milestone: v1.0 MVP

**Shipped:** 2026-05-21
**Phases:** 0–7 | **Plans:** 28 | **Timeline:** 8 days

### What Was Built

1. JWT auth stack — registration, email verification, login/logout, refresh token rotation, password reset
2. AES-256-GCM API key management (TMDB + OMDB) with account management settings
3. Async film enrichment pipeline — TMDB → OMDB (graceful) → Wikipedia (6-step fallback) → Postgres → OpenSearch
4. OpenSearch per-user index with custom analyzer + 40-field mapping
5. Full-text + advanced faceted search with dashboard, sorting, click-through navigation
6. Cinematic film detail page with personal fields, YouTube trailer embed, delete flow
7. Playwright E2E happy-path tests (Desktop + Mobile Chrome) + GitHub Actions CI + README

### What Worked

- **Wave 0 scaffolding pattern:** Writing @Disabled test stubs in plan -01 before production code forces test contracts to be explicit before any implementation. Never had a phase where tests didn't pass at completion.
- **Yolo mode + GSD structure:** Fast execution without confirmation gates, but structured planning artifacts (PLAN.md, VERIFICATION.md) kept quality high.
- **WireMock + MSW fixture strategy:** All external APIs always mocked — TMDB, OMDB, Wikipedia never called in CI. Zero flakiness from external API issues.
- **Testcontainers singleton pattern:** Static `@BeforeAll` container startup shared across all test classes prevents slow per-class container restarts.
- **202 Accepted UX pattern:** Returning immediately from save and polling status eliminated all timeout issues; user experience never blocked on slow external APIs.

### What Was Inefficient

- **Requirements.md traceability not maintained during execution:** Traceability table stayed "Pending" throughout phases 2–7. PROJECT.md was the live truth but REQUIREMENTS.md diverged. Fixed in archive but would be better to update during transitions.
- **Container query attempt for star rating:** Tried CSS container queries which failed due to flex shrink-to-fit sizing — needed a viewport breakpoint approach from the start. One round of rework.
- **Dark theme misunderstanding on detail page:** Applied full dark theme when only top overscroll color was requested — caused a reversal round. Clarify scope before implementing visual changes.
- **Jira ticket batching:** Tickets were closed in multiple batches across sessions rather than at phase-completion time. Per-ticket close immediately after completion (as in CLAUDE.md) would have avoided the cleanup session.

### Patterns Established

- **E2E polling pattern:** `Refresh.WaitFor` on OpenSearch index call + frontend polling until `indexedAt !== null` (not just `status === 'SUCCESS'`) ensures E2E tests don't race against async indexing.
- **Per-user OpenSearch index:** `movies-{userId}` pattern gives data isolation at infra level and trivially extends to multi-user.
- **useHead scoped styles:** `style: [{ innerHTML: '...' }]` in `useHead` injects page-scoped styles that are cleaned up on navigation — right pattern for detail-page-only overscroll color.
- **`auth.getName()` returns email:** In this project, `UserDetailsServiceImpl` uses email as username. All ownership checks must use `userRepository.findByEmail(auth.getName()).getId()`, not parse JWT subject.

### Key Lessons

1. Verify the full scope of a visual change request before implementing ("which elements should change?") — prevents revert rounds.
2. The `withJson()` pattern for OpenSearch index creation (vs. typed builder) is the correct approach with opensearch-java 2.x — document this clearly for future OpenSearch work.
3. For E2E tests involving async pipelines: both the backend write visibility (Refresh.WaitFor) AND the frontend polling condition (indexedAt not null) must be correct together. Either fix alone is insufficient.
4. Container queries report content width on flex shrink-to-fit containers, not available width — use viewport breakpoints for responsive component layout.

### Cost Observations

- Sessions: ~15 sessions across 8 days
- Notable: Yolo mode with structured GSD plans was highly efficient — most plans executed in a single session without clarification loops.

---

## Milestone: v1.1 Wiki Retry & Bulk Import

**Shipped:** 2026-08-29
**Phases:** 8–16 | **Plans:** 28 | **Timeline:** 8 days (2026-08-22 → 2026-08-29)

### What Was Built

1. Wikipedia enrichment attempt tracking (`wiki_last_attempted_at`) + cooldown-filtered, paced async batch-reload
2. Manual per-film Wikipedia retry button on the detail page + a Settings-page batch-reload trigger
3. Bulk Import engine — file upload (semicolon and RFC4180 CSV), TMDB matching with dedup-on-reupload
4. Bulk Import feedback UI — live SSE progress, batch list/detail pages, inline ambiguous/not-found resolution
5. Wikidata-first Wikipedia lookup (P345 IMDb-ID cross-reference) replacing the URL-guessing cascade, later batched via SPARQL (up to 50 IMDb IDs/request) after the REST-based version tripped Wikidata's anonymous rate limiter within 2-3 movies
6. Wiki batch-reload pacing/cooldown fixes + progress UI (live ETA, stop control, stopped-vs-completed distinction)
7. Bulk import correctness fixes — cross-batch dedup bug, multi-stage TMDB auto-match algorithm

### What Worked

- **Live-data UAT as a standing gate, not a one-off:** Phases 13, 14, 15, and 16 each ran a live UAT pass against real data/real browser sessions after mocked tests passed, and every single one found real bugs (rate-limiter behavior, Nuxt's `:is="'NuxtLink'"` compile-time registration gap, CSS layout squeeze, SSE history duplication, retry-eligibility keyed on the wrong column) that WireMock/MSW/Vitest stubs structurally could not surface. Treating "tests pass" and "verified live" as two separate gates repeatedly caught production-breaking bugs before ship.
- **Parallel git worktrees for independent gap-closure plans:** Phase 16's two UAT-found gaps (G-16-2, G-16-3) touched disjoint files and were fixed as separate plans executed in parallel worktrees, then merged — faster than serializing two small, unrelated fixes.
- **Batched external-API calls over per-item calls:** The root-cause fix for the original ~630-movie incident and its Phase-13 rate-limiter recurrence was the same shape both times — replace N per-item external calls with one batched call (SPARQL `VALUES` clause resolving up to 50 IMDb IDs at once). Recognizing the pattern the second time (Phase 13) was fast because Phase 12 had already established it.
- **Deferred items with inline reasoning, not silent scope cuts:** Phase 15's two out-of-scope findings (pre-existing lint warning, full-suite test-isolation flakiness) were documented in a `deferred-items.md` with explicit "why not fixed here" reasoning at discovery time, rather than just being dropped — made them trivial to audit and consciously accept at milestone close.

### What Was Inefficient

- **Debug sessions and quick-task SUMMARY.md files left un-synced after their fix shipped:** Three debug sessions (`bulk-import-not-adding-movies`, `bulk-import-saved-card-link-broken`, `resolve-widget-narrow-grid`) were root-caused correctly but their actual fix landed in a *later* phase's plan (15-04, or Phase 11) without the debug session file being moved to `resolved/` or annotated — same for two quick tasks (`pwp`, `qfm`) that were executed but never got a `SUMMARY.md`. All were only caught by the milestone-close audit gate, requiring a dedicated investigation pass to trace each fix back to its actual commit before archiving. When a debug session's fix is deliberately deferred to a separate plan, that plan's SUMMARY should reference the debug session file directly so it can be closed at the same time, not discovered later.
- **v1.0 phase UAT/verification files never synced after human sign-off:** Phase 01's `01-HUMAN-UAT.md` recorded 5/5 passed back in 2026-05-16, but `01-VERIFICATION.md`'s `status: human_needed` frontmatter was never updated to match — a pure bookkeeping gap carried silently for 3+ months until this milestone's audit caught it. Two other phases (02, 06) never had their `HUMAN-UAT.md` scenarios executed at all despite the app being in daily personal use since v1.0 shipped, and Phase 05 never even had a `HUMAN-UAT.md` file created. Human UAT sign-off should happen at the same session as verification, not deferred indefinitely.
- **Milestone reopened three times after initially appearing complete (Phases 14, 15, 16):** Each reopening was justified (live UAT found real bugs, or the user pulled forward a v2-backlog item), but it meant "milestone complete" was declared and then retracted three times before the actual close. Running the live-data UAT pass one phase earlier, before declaring a phase (and by extension the milestone) done, would have caught the same bugs without the reopen/replan overhead.

### Patterns Established

- **Cooldown timestamp over permanent "not found" flag:** For any external lookup that can start succeeding later (a Wikipedia page created after the fact), record a timestamp + cooldown window rather than a permanent skip flag — re-attempts eventually, never permanently gives up.
- **`resolveComponent('NuxtLink')` for conditional built-in components:** Nuxt 3 only auto-registers built-ins (`NuxtLink`, etc.) into a file's compiled output when it sees a literal `<NuxtLink>` tag in that file's template. A bare string bound to `:is` is invisible to that scan and silently renders an inert custom element in production, even though Vue Test Utils' `global.stubs` masks the gap in tests. Always capture `const X = resolveComponent('X')` in `<script setup>` when conditionally rendering a built-in via `:is`.
- **Chunking/batching logic lives in the client, not the caller:** `WikipediaClient.resolveViaWikidataSparql()` owns the IMDb-ID chunk-size constant so callers (`WikiReloadService`, `BulkImportService`) can pass arbitrarily large ID lists without knowing about batching limits — keeps the "one client, one method" boundary intact.
- **Full-width breakout for content nested inside a grid cell:** An expanding/inline-editing widget that needs to show more content than its trigger's cell width (candidate pickers, expanded rows) needs an explicit `col-span-full` (grid) or full-width block (flex/list) breakout — nesting it as a plain descendant silently constrains it to the parent cell's width.
- **Batch-scoped repository queries need both ownership AND scope keys:** Any repository method that must not leak/reassign rows across a scoping boundary (here: `userId` + `batchId`) needs both keys in every query — `userId` alone is not sufficient defense-in-depth once a second scoping dimension (batches) exists.

### Key Lessons

1. A live-UAT pass against real data/a real browser is a distinct verification gate from "tests pass" — schedule it before declaring a phase done, not as an afterthought that reopens the phase.
2. When a debug session's root-cause fix is deliberately deferred to a later plan, link the debug session file from that plan's SUMMARY so the two get closed together — otherwise the debug session becomes an untracked relic until the next milestone audit finds it.
3. Human UAT sign-off should happen in the same session as the verification report, not left as a `[pending]` placeholder indefinitely — a 3+ month gap between "code verified" and "human confirmed" makes the eventual catch-up costly and the interim status misleading.
4. When an external API's rate limiter is hit, check whether the fix is "pace requests more" or "batch requests instead" — the second is often available (SPARQL `VALUES`, bulk endpoints) and eliminates the rate-limit exposure entirely rather than just slowing into it.

### Cost Observations

- Sessions: multiple sessions across 8 days, including 3 milestone-reopen cycles (Phases 14, 15, 16) each triggered by live-UAT findings or a pulled-forward backlog item
- Notable: the milestone-close audit gate (open debug sessions, unclosed UAT gaps, missing quick-task summaries) caught real, un-synced bookkeeping across both v1.0 and v1.1 phases that had gone unnoticed for months — worth treating as a standing pre-close checklist rather than a one-off click-through

---

## Cross-Milestone Trends

| Metric | v1.0 | v1.1 |
|--------|------|------|
| Days to ship | 8 | 8 |
| Phases | 8 (0–7) | 9 (8–16) |
| Plans | 28 | 28 |
| Commits | 258 | 314 |
| Files | 369 | 222 changed |
| Lines | ~62k | +33,464/−186 |
| Test strategy | WireMock + Testcontainers + MSW + Playwright | WireMock + Testcontainers + MSW + live-data UAT gate |
| Rework rounds | 3 (gap closure plan 03-06, star rating, overscroll) | 3 milestone reopens (Phases 14, 15, 16) + multiple live-UAT gap-closure plans per phase |
