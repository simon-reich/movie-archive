---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: UX Polish, Wiki Enrichment & Personal Lists
status: planning
last_updated: "2026-08-29T17:46:06.114Z"
last_activity: 2026-08-29
progress:
  total_phases: 0
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-29)

**Core value:** Archivieren und finden — a film must be saveable in seconds and findable just as fast.
**Current focus:** v1.1 milestone complete — no active phase

## Current Position

Phase: Not started (defining requirements)
Plan: —
Status: Defining requirements
Last activity: 2026-08-29 — Milestone v2.0 started

## Performance Metrics

**Velocity:**

- Total plans completed: 28 (v1.0)
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

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Key decisions relevant to v1.1:

- [v1.1 roadmap]: Cooldown-Zeitstempel (`wiki_last_attempted_at`) statt permanentem "not found"-Flag — Wikipedia-Seiten entstehen über Zeit neu; permanenter Skip würde das dauerhaft verpassen
- [v1.1 roadmap]: Batch-Reload ist ein Backend-/Admin-Endpoint (analog `ReindexController`-Pattern) — keine dedizierte Trigger-UI in den Requirements gefordert
- [v1.1 roadmap]: Mehrdeutige Bulk-Import-Treffer werden nie automatisch geraten — immer manuelle Prüfung (Out of Scope: Best-Match-Raten)
- [v1.1 roadmap]: Phase 8 (data model + batch backend) trennt sich von Phase 9 (manual per-film retry, full stack) — jede Requirement-Gruppe bleibt vollständig in einer Phase
- [v1.1 roadmap]: Phase 10 (Bulk-Import-Engine: Upload/Parse/Match/Save/Dedup) trennt sich von Phase 11 (Live-Progress + Ergebnisübersicht) — Engine zuerst, Feedback-UI konsumiert sie danach
- [Phase 10 UAT]: Bulk-Import-Format bleibt strikt `Title;OriginalTitle;Year` (Original Title optional leer) — UI zeigt jetzt einen Format-Hinweis direkt im Bulk-Import-Bereich und ein komplett unparsbarer Upload wird synchron mit 400 + spezifischer Meldung abgelehnt statt still als "Import started" zu erscheinen (G-10-1, 10-03-PLAN.md)
- [Phase 12]: Wikidata P345 (IMDb-ID) Cross-Reference löst Wikipedia-Artikel direkt auf statt bis zu 10 URL-Kandidaten zu raten — Fallback-Kaskade bleibt für Filme ohne Wikidata-Eintrag erhalten
- [Phase 13]: REST-basierte Wikidata-Suche (CirrusSearch + Sitelinks) ersetzt durch gebatchte SPARQL-Query (bis zu 50 IMDb-IDs/Request) — REST-Suche traf Wikidata's anonymen Rate-Limiter nach 2-3 Filmen unabhängig vom Pacing; `WikiReloadService.batchReload()` und `BulkImportService.runImport()` prefetchen jetzt einmal pro Lauf statt einmal pro Film
- [Phase 14]: Live-UAT gegen echte Daten (368-382 eligible Filme) fand und fixte 5 reale Bugs, die gemockte Tests nicht zeigen konnten (Stop-Button unsichtbar während Prefetch, SPARQL-Prefetch-Burst statt Chunk-Interleaving, ETA ohne Pacing-Delay massiv zu niedrig, Stop-Button ohne Wartezeit-Feedback, ein Frontend-Typfehler); Wikidata-Prefetch läuft jetzt chunk-weise interleaved mit der Movie-Verarbeitung statt den gesamten eligible-Satz upfront in einem Rate-Limit-tripenden Burst aufzulösen; `wiki.retry.pacing-delay-ms` Default per Live-A/B-Test auf 20s justiert (war 30s)
- [Phase 15]: Milestone-Abschluss — View-Toggle/Movie-Links/Inline-Resolve, echtes CSV-Parsing (RFC4180); 3 live-gefundene Bugs (NuxtLink-`:is`-Binding, Resolve-Widget-Layout, Titel-Truncation) in derselben Session gefixt
- [Phase 16]: Endgültiger Milestone-Abschluss — Cross-Batch-Dedup-Fix (CR-01), Stopped-vs-Completed-Progress-UI (WR-02), mehrstufiger TMDB-Match; live-UAT fand 2 weitere Bugs (G-16-2 History-Duplikat, G-16-3 endlose Wiki-Reload-Retries bei bereits gefundenen Seiten), beide über parallele Git-Worktree-Gap-Closure-Pläne gefixt und live bestätigt; Security-Review 7/7 Threats geschlossen

### Pending Todos

- 2026-08-28-create-api-contract-doc-for-future-flutter-port — Create a dedicated API-contract doc (endpoints, payload/SSE shapes, auth rules, rate-limit/pacing timing) to prep for a future Flutter frontend reusing the existing backend as-is; not urgent, no Flutter work started yet [minor] — not tied to any milestone, stays open

### Blockers/Concerns

None.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|

### Roadmap Evolution

- Phase 12 edited: edited fields: goal, depends_on
- Phase 13 added: Wikidata SPARQL Batch Lookup
- Phase 14 added: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI — milestone re-opened after live verification of Phase 13 against real dev environment (2026-08-27) found real-world rate limiting the mocked tests couldn't surface, plus a cooldown-marking bug and a missing progress UI
- Phase 15 added: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing — folds in the 2026-08-24/25 loose todos so v1.1 closes with the import feature actually finished
- Phase 15 completed 2026-08-28 (6/6 plans, incl. 3 gap-closure plans from live UAT); two live-found SSE bugs (AuthorizationDeniedException on async redispatch; wiki-reload progress permanently frozen after first run) fixed via standalone debug sessions before milestone close, both committed and archived to .planning/debug/resolved/
- Phase 16 added 2026-08-29: milestone re-opened one more time before close — folds in the cross-batch bulk-import dedup bug (major, pre-existing since Phase 10), the deferred wiki-reload stopped-vs-completed UI gap (WR-02, Phase 14), and a new multi-stage TMDB auto-match algorithm decided by the user (title-only search first, single result auto-taken, multi-result narrowed by exact title+year, else AMBIGUOUS)
- Phase 16 completed 2026-08-29 (4/4 plans, incl. 2 gap-closure plans from live UAT executed in parallel git worktrees); v1.1 milestone fully complete (9/9 phases)

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

Last session: 2026-08-29T18:30:00.000Z
Stopped at: v1.1 milestone complete — ready for /gsd-complete-milestone v1.1

## Operator Next Steps

- Start the next milestone with /gsd-new-milestone
