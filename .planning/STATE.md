---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Enrichment Reliability & Bulk Import
current_phase: 15
current_phase_name: bulk-import-page-completion-view-toggle-movie-links-real-csv
status: planning
stopped_at: Phase 15 context gathered
last_updated: "2026-08-28T11:29:13.565Z"
last_activity: 2026-08-28
last_activity_desc: Phase 15 context gathered
state_head: f7158763f2907abb24a39bccccdad00e6e9b5ba8
progress:
  total_phases: 8
  completed_phases: 7
  total_plans: 21
  completed_plans: 18
  percent: 86
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-28)

**Core value:** Archivieren und finden — a film must be saveable in seconds and findable just as fast.
**Current focus:** Phase 15 — Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing

## Current Position

Phase: 15 (bulk-import-page-completion-view-toggle-movie-links-real-csv) — READY TO EXECUTE
Plan: Not started
Status: Context gathered — ready to plan
Last activity: 2026-08-28 — Phase 15 context gathered (15-CONTEXT.md, 17 decisions across view toggle, movie links, inline resolve, CSV format)

Progress: 7/8 phases complete (88%) — Phase 15 (import page completion) is the last phase before v1.1 can close

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

- 2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link — Enhance bulk import batch detail page: view toggle, movie links, inline ambiguous resolve [minor]
- 2026-08-24-support-real-csv-parsing-for-bulk-import — Support real CSV parsing for bulk import (and matching CSV export) [minor]
- 2026-08-27-distinguish-stopped-vs-completed-in-progress-ui — Distinguish "stopped early" from "fully completed" in the wiki-reload progress UI (WR-02 from 14-REVIEW.md; deferred ProgressState schema change) [minor]
- 2026-08-27-authorizationdeniedexception-on-sse-emitter-complete — AuthorizationDeniedException logged on SseEmitter.complete() async re-dispatch; pre-existing pattern shared with bulk-import's progress endpoint, appears to be log noise only (response already committed) but needs proper root-causing [minor]
- 2026-08-28-create-api-contract-doc-for-future-flutter-port — Create a dedicated API-contract doc (endpoints, payload/SSE shapes, auth rules, rate-limit/pacing timing) to prep for a future Flutter frontend reusing the existing backend as-is; not urgent, no Flutter work started yet [minor]

### Blockers/Concerns

None.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260519-fcg | Fix all ESLint errors and warnings from CI lint output across the frontend | 2026-05-19 | b944dc0 | [260519-fcg-fix-all-eslint-errors-and-warnings-from-](./quick/260519-fcg-fix-all-eslint-errors-and-warnings-from-/) |
| 2 | 260826-pwp: Set wiki.retry.cooldown-days default to 0 temporarily (dev testing) | 2026-08-26 | 4f139c0 | — |
| 3 | 260826-qfm: Give Wikidata calls their own longer request pacing (3000ms default) | 2026-08-26 | d7cb20a | — |

### Roadmap Evolution

- Phase 12 edited: edited fields: goal, depends_on
- Phase 13 added: Wikidata SPARQL Batch Lookup
- Phase 14 added: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI — milestone re-opened after live verification of Phase 13 against real dev environment (2026-08-27) found real-world rate limiting the mocked tests couldn't surface, plus a cooldown-marking bug and a missing progress UI
- Phase 15 added: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing — folds in the 2026-08-24/25 loose todos so v1.1 closes with the import feature actually finished

## Session Continuity

**Resume file:** .planning/phases/15-bulk-import-page-completion-view-toggle-movie-links-real-csv/15-CONTEXT.md

Last session: 2026-08-28T12:30:00.000Z
Stopped at: Phase 15 context gathered
