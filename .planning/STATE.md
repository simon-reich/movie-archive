---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Enrichment Reliability & Bulk Import
current_phase: 16
status: ready_to_plan
stopped_at: Phase 16 added (bulk-import dedup fix, wiki-reload stop-vs-complete clarity, multi-stage TMDB matching) — v1.1 not yet closed
last_updated: "2026-08-29T00:00:00.000Z"
last_activity: 2026-08-29
last_activity_desc: Added Phase 16 to v1.1 before milestone close
state_head: 6d0d240
progress:
  total_phases: 9
  completed_phases: 8
  total_plans: 24
  completed_plans: 24
  percent: 89
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-28)

**Core value:** Archivieren und finden — a film must be saveable in seconds and findable just as fast.
**Current focus:** Phase 16 — Bulk Import Correctness & Wiki-Reload Progress Clarity

## Current Position

Phase: 16
Plan: Not started
Status: Ready to plan
Last activity: 2026-08-29 — Phase 16 added

Progress: 8/9 phases complete (89%) — Phase 16 (bulk-import dedup fix, wiki-reload stop-vs-complete clarity, multi-stage TMDB matching) is the last phase before v1.1 can close

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

### Pending Todos

- 2026-08-28-create-api-contract-doc-for-future-flutter-port — Create a dedicated API-contract doc (endpoints, payload/SSE shapes, auth rules, rate-limit/pacing timing) to prep for a future Flutter frontend reusing the existing backend as-is; not urgent, no Flutter work started yet [minor] — NOT part of Phase 16, stays open
- 2026-08-27-distinguish-stopped-vs-completed-in-progress-ui — Distinguish "stopped early" from "fully completed" in the wiki-reload progress UI (WR-02 from 14-REVIEW.md; deferred ProgressState schema change) [minor] — folded into Phase 16
- 2026-08-28-fix-cross-batch-line-reassignment-in-bulk-import-dedup — BulkImportService.findExistingRow() dedups by user+title+year only, not batchId, silently reassigning lines across batches; pre-existing since Phase 10, found during Phase 15 code review (CR-01) [major] — folded into Phase 16

### Blockers/Concerns

None.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260519-fcg | Fix all ESLint errors and warnings from CI lint output across the frontend | 2026-05-19 | b944dc0 | [260519-fcg-fix-all-eslint-errors-and-warnings-from-](./quick/260519-fcg-fix-all-eslint-errors-and-warnings-from-/) |
| 2 | 260826-pwp: Set wiki.retry.cooldown-days default to 0 temporarily (dev testing) | 2026-08-26 | 4f139c0 | — |
| 260828-qh2 | Bulk import: skip Wikipedia fetch during import, rely on WikiReloadService batch-reload to backfill later | 2026-08-28 | 635a744 | [260828-qh2-bulk-import-skip-wikipedia-fetch-during-](./quick/260828-qh2-bulk-import-skip-wikipedia-fetch-during-/) |
| 3 | 260826-qfm: Give Wikidata calls their own longer request pacing (3000ms default) | 2026-08-26 | d7cb20a | — |

### Roadmap Evolution

- Phase 12 edited: edited fields: goal, depends_on
- Phase 13 added: Wikidata SPARQL Batch Lookup
- Phase 14 added: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI — milestone re-opened after live verification of Phase 13 against real dev environment (2026-08-27) found real-world rate limiting the mocked tests couldn't surface, plus a cooldown-marking bug and a missing progress UI
- Phase 15 added: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing — folds in the 2026-08-24/25 loose todos so v1.1 closes with the import feature actually finished
- Phase 15 completed 2026-08-28 (6/6 plans, incl. 3 gap-closure plans from live UAT); two live-found SSE bugs (AuthorizationDeniedException on async redispatch; wiki-reload progress permanently frozen after first run) fixed via standalone debug sessions before milestone close, both committed and archived to .planning/debug/resolved/
- Phase 16 added 2026-08-29: milestone re-opened one more time before close — folds in the cross-batch bulk-import dedup bug (major, pre-existing since Phase 10), the deferred wiki-reload stopped-vs-completed UI gap (WR-02, Phase 14), and a new multi-stage TMDB auto-match algorithm decided by the user (title-only search first, single result auto-taken, multi-result narrowed by exact title+year, else AMBIGUOUS)

## Session Continuity

**Resume file:** .planning/phases/15-bulk-import-page-completion-view-toggle-movie-links-real-csv/15-CONTEXT.md

Last session: 2026-08-28T12:30:00.000Z
Stopped at: Phase 15 complete — all phases complete
