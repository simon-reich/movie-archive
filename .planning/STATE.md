---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Enrichment Reliability & Bulk Import
status: planning
last_updated: "2026-08-22T18:50:54.510Z"
last_activity: 2026-08-22
progress:
  total_phases: 0
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-16)

**Core value:** Archivieren und finden — a film must be saveable in seconds and findable just as fast.

## Current Position

Phase: Not started (defining requirements)
Plan: —
Status: Defining requirements
Last activity: 2026-08-22 — Milestone v1.1 started

## Performance Metrics

**Velocity:**

- Total plans completed: 28
- Average duration: ~23 min/plan

**By Phase:**

| Phase | Plans | Status |
|-------|-------|--------|
| 0. Setup & Infrastructure | — | COMPLETE |
| 1. Authentication | 3 | COMPLETE — 2026-05-16 |
| 2. Settings & API Keys | 3 | COMPLETE — 2026-05-16 |
| 3. Save Movie Flow | 6 | COMPLETE |
| 4. OpenSearch Indexing | 3 | COMPLETE |
| 5. Search | 4 | COMPLETE |
| 6. Movie Detail & Personal Fields | 6 | COMPLETE |
| 7. E2E Tests, Mobile, Polish, README | 3 | COMPLETE — 2026-05-21 |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Key decisions:

- [Phase 1]: JwtAuthFilter without @Component — instantiate directly in SecurityFilterChain to prevent double-registration
- [Phase 1]: grace_until = now + 5s on refresh token rotation — handles concurrent tab race condition
- [Phase 1]: forgotPassword always returns 200 — enumeration protection
- [Phase 1]: Testcontainers static block (not @Testcontainers/@Container) — prevents container stop between test classes
- [Phase 1]: vi.mock('#app/composables/router') not vi.stubGlobal — Nuxt auto-import resolves from module, not global scope
- [Phase 3]: OpenSearch Custom Analyzer must be finalized in Phase 4 but ensureIndexExists() called on first Phase 3 write — Phase 4 plan must be ready before Phase 3 executes
- [Phase 4]: Use withJson() for index creation (opensearch-java typed builder has a bug for custom analyzers — GitHub issue #1510)
- [Phase 4]: JWT subject = userId UUID string per plan design; HOWEVER UserDetailsServiceImpl returns email as username — auth.getName() returns email, not UUID. Ownership checks must use userRepository.findByEmail(auth.getName()).getId()
- [Phase 4]: GenericContainer for OS Testcontainers (opensearch-testcontainers 4.x is for OS 3.x — incompatible)
- [Phase 4, Plan 01]: AbstractOpenSearchTest extends AbstractIntegrationTest so Postgres + OS singletons coexist
- [Phase 4, Plan 01]: _skeleton:true root marker in movies-index.json so plan 04-02 can detect and replace the file
- [Phase 4, Plan 02]: OpenSearch 2.x does not support 'flattened' type — use 'object' for rating_list, 'object+enabled:false' for poster/backdrop/video lists
- [Phase 4, Plan 02]: kstem stems plurals (films->film) but not gerunds (running->running) — use plural forms for stemming tests
- [Phase 4, Plan 02]: IndexingService.index() does NOT set indexed_at — EnrichmentService does after calling it (D-01 contract)
- [Phase 4, Plan 03]: auth.getName() returns email (UserDetailsServiceImpl username=email) — ReindexController resolves userId from email for ownership check
- [Phase 4, Plan 03]: (user_id, tmdb_id) unique constraint — test movie helpers must use unique tmdbId per movie per user
- [Phase 7]: OpenSearch Refresh.WaitFor on index() call — document immediately searchable, fixes E2E Mobile Chrome race condition
- [Phase 7]: Star rating uses Tailwind viewport breakpoints (grid-cols-5 sm:grid-cols-10 md:grid-cols-5) — container queries failed due to flex shrink-to-fit sizing
- [Phase 7]: useHead injects html { background-color: #111 } on detail page — browsers use <html> background for top-overscroll gutter area

### Pending Todos

None.

### Blockers/Concerns

None.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260519-fcg | Fix all ESLint errors and warnings from CI lint output across the frontend | 2026-05-19 | b944dc0 | [260519-fcg-fix-all-eslint-errors-and-warnings-from-](./quick/260519-fcg-fix-all-eslint-errors-and-warnings-from-/) |

## Session Continuity

Last session: 2026-05-21
Stopped at: v1.0 complete — all phases, plans, and Jira tickets closed
