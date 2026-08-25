# Roadmap: MovieArchive

## Milestones

- ✅ **v1.0 MVP** — Phases 0–7, 28 plans (shipped 2026-05-21) — [archive](milestones/v1.0-ROADMAP.md)
- 🚧 **v1.1 Enrichment Reliability & Bulk Import** — Phases 8–11 (in progress)

## Phases

<details>
<summary>✅ v1.0 MVP (Phases 0–7) — SHIPPED 2026-05-21</summary>

- [x] Phase 0: Setup & Infrastructure — COMPLETE 2026-05-15
- [x] Phase 1: Authentication (3/3 plans) — COMPLETE 2026-05-16
- [x] Phase 2: Settings & API Keys (3/3 plans) — COMPLETE 2026-05-16
- [x] Phase 3: Save Movie Flow (6/6 plans) — COMPLETE 2026-05-17
- [x] Phase 4: OpenSearch Indexing (3/3 plans) — COMPLETE 2026-05-18
- [x] Phase 5: Search (4/4 plans) — COMPLETE 2026-05-19
- [x] Phase 6: Movie Detail & Personal Fields (6/6 plans) — COMPLETE 2026-05-20
- [x] Phase 7: Polish & Quality (3/3 plans) — COMPLETE 2026-05-21

</details>

### 🚧 v1.1 Enrichment Reliability & Bulk Import (In Progress)

**Milestone Goal:** Enrichment-Lücken sichtbar und behebbar machen — automatisiert/bulk und manuell pro Film — und Bulk-Import als vollwertiges In-App-Feature mit Nachvollziehbarkeit.

**Trigger:** Bulk-Import von ~630 Filmen via externes Node-Script trieb Wikipedia offenbar ins Rate-Limiting — ~89% der importierten Filme blieben ohne Wikipedia-Daten, silent-failed ohne Retry-Möglichkeit (siehe PROJECT.md Context).

- [x] **Phase 8: Wiki Enrichment Tracking & Batch Reload** - Track Wikipedia attempt timestamps per film and batch-reload films missing wiki data with cooldown filtering and paced requests (completed 2026-08-23)
- [x] **Phase 9: Manual Wiki Retry** - Per-film retry button on the detail page for films without Wikipedia data, with immediate result feedback (completed 2026-08-23)
- [x] **Phase 10: Bulk Import Engine** - Upload a title+year list in Add Film; auto-match and save unique TMDB hits, flag ambiguous ones, skip already-imported lines on re-upload (completed 2026-08-24)
- [x] **Phase 11: Bulk Import Feedback UI** - Live progress and a per-line result overview (title, poster, status) for bulk imports (completed 2026-08-25)

## Phase Details

### Phase 8: Wiki Enrichment Tracking & Batch Reload

**Goal**: The system tracks every Wikipedia enrichment attempt per film and can batch-reload films missing Wikipedia data without re-triggering rate limiting.
**Depends on**: Nothing new — builds on the shipped v1.0 enrichment pipeline (Phase 3)
**Requirements**: ENRICH-01, ENRICH-02, ENRICH-03
**Success Criteria** (what must be TRUE):

  1. Every Wikipedia enrichment attempt (success or failure) records a `wiki_last_attempted_at` timestamp on the film.
  2. A batch-reload endpoint reprocesses only films without Wikipedia data whose last attempt is outside the cooldown window (or never attempted); films that failed recently, within the cooldown, are skipped.
  3. Batch-reload requests to Wikipedia are paced with a delay between calls, so reprocessing many eligible films does not fire near-simultaneous requests.

**Plans**: 2/2 plans executed

- [x] 08-01-PLAN.md — Wiki attempt tracking (both paths) + minimal end-to-end batch-reload endpoint (tracer)
- [x] 08-02-PLAN.md — Cooldown-window eligibility filtering + async execution with pacing + concurrency safeguards

### Phase 9: Manual Wiki Retry

**Goal**: Users can manually retry Wikipedia enrichment for a single film from its detail page and immediately see whether it succeeded.
**Depends on**: Phase 8
**Requirements**: ENRICH-04, ENRICH-05
**Success Criteria** (what must be TRUE):

  1. On the detail page of a film without Wikipedia data, the user sees a Retry button.
  2. Clicking Retry triggers a single Wikipedia enrichment attempt for that film only (not a batch).
  3. On success, the film's Wikipedia plot/critics data appears on the page.
  4. On failure, the user sees a clear message that no Wikipedia data was found, and the film's `wiki_last_attempted_at` is updated so the batch-reload cooldown reflects the manual attempt too.

**Plans**: 2/2 plans executed

Plans:

- [x] 09-01-PLAN.md — Per-film Wikipedia retry (ENRICH-04, ENRICH-05): POST /movies/{id}/retry-wiki + detail-page Retry button
- [x] 09-02-PLAN.md — Batch-reload trigger button (folded todo): GET /users/me + Settings-page reload button

### Phase 10: Bulk Import Engine

**Goal**: Users can upload a title+year list in the Add Film area and have the system automatically resolve and save unique TMDB matches, flag ambiguous ones for manual review, and safely skip already-imported lines on re-upload.
**Depends on**: Nothing new — reuses the shipped v1.0 Save Movie Flow (Phase 3); independent of Phase 8–9
**Requirements**: IMPORT-01, IMPORT-02, IMPORT-03, IMPORT-04, IMPORT-07
**Success Criteria** (what must be TRUE):

  1. User can upload a text file (one film per line: Title, optional Original Title in parentheses, Year) in the Add Film area.
  2. Each line is parsed and matched against TMDB search results filtered by year.
  3. A line with exactly one year-matching candidate is automatically saved via the existing save flow (idempotent, no duplicates).
  4. A line with multiple year-matching candidates is marked ambiguous instead of being auto-guessed.
  5. Re-uploading the same file skips lines already saved in a previous run — no duplicate TMDB calls and no duplicate saves for those lines.

**Plans**: 3/3 plans executed

Plans:

- [x] 10-01-PLAN.md — Bulk import engine: multipart upload, parse/match/save, dedup-skip-on-reupload (IMPORT-01/02/03/04/07)
- [x] 10-02-PLAN.md — Add Film page: bulk import upload trigger (IMPORT-01)
- [x] 10-03-PLAN.md — Gap closure: format hint + pre-flight all-lines-unparseable 400 detection (G-10-1, IMPORT-01)

**UI hint**: yes

### Phase 11: Bulk Import Feedback UI

**Goal**: Users can track an in-progress bulk import and review a clear, per-line outcome once it completes.
**Depends on**: Phase 10
**Requirements**: IMPORT-05, IMPORT-06
**Success Criteria** (what must be TRUE):

  1. While an import is running, the user sees a live progress indicator (films processed / total films).
  2. After the import completes, the user sees a results list showing, per line: title, poster (if found), and status (saved / ambiguous / not found / parse error).

**Plans**: 5 plans

Plans:

- [x] 11-01-PLAN.md — Backend: batch persistence + poster capture (D-02/D-04, tracer)
- [x] 11-02-PLAN.md — Backend: live progress SSE endpoint (D-01)
- [x] 11-03-PLAN.md — Backend: batch list + detail GET endpoints (D-03/D-05/D-06)
- [x] 11-04-PLAN.md — Frontend: progress + results page, reachable from Add Film
- [x] 11-05-PLAN.md — Frontend: batch list page + nav entry (manual browser walkthrough still pending human sign-off)

**UI hint**: yes

## Progress

| Phase | Milestone | Plans | Status | Completed |
|-------|-----------|-------|--------|-----------|
| 0. Setup & Infrastructure | v1.0 | -/- | ✅ Complete | 2026-05-15 |
| 1. Authentication | v1.0 | 3/3 | ✅ Complete | 2026-05-16 |
| 2. Settings & API Keys | v1.0 | 3/3 | ✅ Complete | 2026-05-16 |
| 3. Save Movie Flow | v1.0 | 6/6 | ✅ Complete | 2026-05-17 |
| 4. OpenSearch Indexing | v1.0 | 3/3 | ✅ Complete | 2026-05-18 |
| 5. Search | v1.0 | 4/4 | ✅ Complete | 2026-05-19 |
| 6. Movie Detail & Personal Fields | v1.0 | 6/6 | ✅ Complete | 2026-05-20 |
| 7. Polish & Quality | v1.0 | 3/3 | ✅ Complete | 2026-05-21 |
| 8. Wiki Enrichment Tracking & Batch Reload | v1.1 | 0/TBD | Complete    | 2026-08-23 |
| 9. Manual Wiki Retry | v1.1 | 0/2 | Complete    | 2026-08-23 |
| 10. Bulk Import Engine | v1.1 | 0/2 | Complete    | 2026-08-24 |
| 11. Bulk Import Feedback UI | v1.1 | 0/5 | Complete    | 2026-08-25 |

### Phase 12: Wikidata-based Wikipedia lookup

**Goal:** Wikipedia lookup uses the Wikidata IMDb-ID cross-reference (property P345) first for a direct, unambiguous article resolution, instead of guessing up to 10 URL candidates; falls back to the existing candidate search when no Wikidata link exists.
**Requirements**: TBD
**Depends on:** None — independent WikipediaClient improvement, unrelated to the Bulk Import phases
**Plans:** 0 plans

Plans:

- [ ] TBD (run /gsd-plan-phase 12 to break down)
