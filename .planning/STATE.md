---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: UX Polish, Wiki Enrichment & Personal Lists
status: planning
last_updated: "2026-08-29T19:56:00.000Z"
last_activity: 2026-08-29
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-29)

**Core value:** Archivieren und finden — a film must be saveable in seconds and findable just as fast.
**Current focus:** v2.0 roadmap created — ready to plan Phase 17

## Current Position

Phase: Not started (Phase 17 of 22 — v2.0 spans Phases 17–22)
Plan: —
Status: Ready to plan
Last activity: 2026-08-29 — ROADMAP.md created for v2.0 (6 phases, 25/25 requirements mapped)

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**

- Total plans completed: 56 (v1.0: 28, v1.1: 28)
- Average duration: ~23 min/plan (v1.0)

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
| 8. Wiki Enrichment Tracking & Batch Reload | 2 | COMPLETE — 2026-08-23 |
| 9. Manual Wiki Retry | 2 | COMPLETE — 2026-08-23 |
| 10. Bulk Import Engine | 3 | COMPLETE — 2026-08-24 |
| 11. Bulk Import Feedback UI | 5 | COMPLETE — 2026-08-25 |
| 12. Wikidata-based Wikipedia lookup | 1 | COMPLETE — 2026-08-27 |
| 13. Wikidata SPARQL Batch Lookup | 3 | COMPLETE — 2026-08-27 |
| 14. Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI | 2 | COMPLETE — 2026-08-28 |
| 15. Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing | 6 | COMPLETE — 2026-08-28 |
| 16. Bulk Import Correctness & Wiki-Reload Progress Clarity | 4 | COMPLETE — 2026-08-29 |
| 17. Bulk Import UX Polish | TBD | Not started |
| 18. Search UX Polish | TBD | Not started |
| 19. Detail Page, Wikipedia Content & Rating Redesign | TBD | Not started |
| 20. Dashboard/Hub Expansion | TBD | Not started |
| 21. Index CSV Backup & Restore | TBD | Not started |
| 22. Personal Lists Foundation | TBD | Not started |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Key decisions relevant to v1.1 carry forward unchanged (see PROJECT.md). New for v2.0 roadmap:

- [v2.0 roadmap]: Detail-page work (DETAIL-06..09) and Wikipedia-content work (WIKI-01..03) bundled into one phase (19) — both touch the same page/data path (parser HTML instead of wikitext, sanitized, rendered with real paragraphs); Rating redesign (RATE-01/02) folded into the same phase since it also lives on the detail page and is small/self-contained
- [v2.0 roadmap]: Bulk Import (17) and Search (18) kept as two sequential phases rather than one — Phase 18 depends on Phase 17 because both need the same no-poster table list-view component (IMPORT-11/SRCH-07), built first in Bulk Import and reused in Search
- [v2.0 roadmap]: Dashboard/Hub Expansion (20) depends on Phase 17 for its Batch-Wiki-Reload card's live-progress + link-to-detail-page pattern, but not on Phase 19 — the underlying wiki-reload backend already shipped in v1.1, this is a UI relocation plus a new mini progress/monitor
- [v2.0 roadmap]: Index CSV Backup & Restore (21) kept fully independent of Bulk Import's CSV pipeline — different purpose (backup/merge vs. TMDB-matched discovery), per REQUIREMENTS.md Out of Scope note
- [v2.0 roadmap]: Personal Lists Foundation (22) kept standalone — new entity (list + list-movie join), no dependency on any other v2.0 phase; PDF export explicitly deferred to v3

### Pending Todos

- 2026-08-28-create-api-contract-doc-for-future-flutter-port — Create a dedicated API-contract doc (endpoints, payload/SSE shapes, auth rules, rate-limit/pacing timing) to prep for a future Flutter frontend reusing the existing backend as-is; not urgent, no Flutter work started yet [minor] — not tied to any milestone, stays open
- 2026-08-31-backend-full-suite-remaining-ci-flakiness — Backend CI still not reliably green after 3 fixed debug sessions (hang, FK-cascade, Hikari pool sizing): EnrichmentIntegrationTest fails on GitHub's 2-core runner but not locally (likely the new maximum-pool-size=5 cap too tight under CI load), plus AuthIntegrationTest 429s from unreset Bucket4j rate-limiter state across the shared JVM test run [major] — E2E Tests and Frontend CI are both green; only the backend full-suite CI job is affected; start a fresh `/gsd-debug` session when picked back up

### Blockers/Concerns

None.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260829-spl | Remove unused PlusCircleIcon import in frontend/pages/movies/[id].vue:3 (CI ESLint failure) | 2026-08-29 | 24b44ce | [260829-spl-remove-unused-pluscircleicon-import-in-f](./quick/260829-spl-remove-unused-pluscircleicon-import-in-f/) |

### Roadmap Evolution

- v2.0 ROADMAP.md created 2026-08-29: 6 phases (17–22) derived from 25 v2.0 requirements across 8 categories (Bulk Import, Search, Detail Page, Rating UI, Wikipedia Enrichment, Dashboard/Hub, Index Backup, Personal Lists) — 25/25 requirements mapped, no orphans

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| debug_sessions | knowledge-base | unknown (scanner false-positive — resolved-sessions index, not a debug session) | 2026-08-29 | v1.1 |
| todos | 2026-08-28-create-api-contract-doc-for-future-flutter-port.md | pending (genuinely still open, carried to next milestone) | 2026-08-29 | v1.1 |
| deferred_items | Phase 15/deferred-items.md: `frontend/pages/movies/[id].vue:3` — ESLint error `'PlusCircleIcon' is defined but never used` (pre-existing, out of scope) | acknowledged | 2026-08-29 | v1.1 |
| deferred_items | Phase 15/deferred-items.md: Full-suite `./gradlew check` cross-class test isolation flakiness (pre-existing test-infrastructure gap, out of scope) | acknowledged | 2026-08-29 | v1.1 |

## Session Continuity

**Resume file:** None

Last session: 2026-08-31T10:35:00.000Z
Stopped at: v2.0 ROADMAP.md created — 6 phases (17–22), 25/25 requirements mapped; post-push CI triage session closed out. Resolved: ESLint fix, e2e-login-redirect-flake, backend-ci-tests-hang, fullsuite-fk-isolation-flakiness (4 debug/quick sessions, all committed and pushed). E2E Tests and Frontend CI are both green as of commit `16ab790`. Backend CI full-suite job is not yet reliably green — tracked as pending todo 2026-08-31-backend-full-suite-remaining-ci-flakiness, deliberately deferred (user is switching to another project). Phase 17 planning has NOT started yet.

## Operator Next Steps

- Optional: pick up `2026-08-31-backend-full-suite-remaining-ci-flakiness` via a fresh `/gsd-debug` session to get Backend CI fully green
- Run `/gsd-plan-phase 17` to start planning Phase 17: Bulk Import UX Polish (v2.0 roadmap is ready, execution hasn't started)
