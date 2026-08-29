# Roadmap: MovieArchive

## Milestones

- ✅ **v1.0 MVP** — Phases 0–7, 28 plans (shipped 2026-05-21) — [archive](milestones/v1.0-ROADMAP.md)
- ✅ **v1.1 Wiki Retry & Bulk Import** — Phases 8–16, 28 plans (shipped 2026-08-29) — [archive](milestones/v1.1-ROADMAP.md)
- 🚧 **v2.0 UX Polish, Wiki Enrichment & Personal Lists** — Phases 17–22 (in progress)

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

<details>
<summary>✅ v1.1 Wiki Retry & Bulk Import (Phases 8–16) — SHIPPED 2026-08-29</summary>

- [x] Phase 8: Wiki Enrichment Tracking & Batch Reload (2/2 plans) — COMPLETE 2026-08-23
- [x] Phase 9: Manual Wiki Retry (2/2 plans) — COMPLETE 2026-08-23
- [x] Phase 10: Bulk Import Engine (3/3 plans) — COMPLETE 2026-08-24
- [x] Phase 11: Bulk Import Feedback UI (5/5 plans) — COMPLETE 2026-08-25
- [x] Phase 12: Wikidata-based Wikipedia Lookup (1/1 plan) — COMPLETE 2026-08-27
- [x] Phase 13: Wikidata SPARQL Batch Lookup (3/3 plans) — COMPLETE 2026-08-27
- [x] Phase 14: Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI (2/2 plans) — COMPLETE 2026-08-28
- [x] Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing (6/6 plans) — COMPLETE 2026-08-28
- [x] Phase 16: Bulk Import Correctness & Wiki-Reload Progress Clarity (4/4 plans) — COMPLETE 2026-08-29

</details>

### 🚧 v2.0 UX Polish, Wiki Enrichment & Personal Lists (In Progress)

**Milestone Goal:** Lift Bulk Import and Search onto a consistent, information-dense list UI, upgrade the detail page and rating display, make Wikipedia content richer and readable, expand the dashboard hub into a real overview (incl. batch wiki-reload monitor), add index CSV import/export, and lay the foundation for Personal Lists (PDF export follows in v3).

- [ ] **Phase 17: Bulk Import UX Polish** - Live progress bar directly on the Add Film page, live poster pop-in per finished film on the batch detail page, and a no-poster table list-view for batch results
- [ ] **Phase 18: Search UX Polish** - Spelled-out language/country filter values, reversible sort direction, and a no-poster table list-view for search results
- [ ] **Phase 19: Detail Page, Wikipedia Content & Rating Redesign** - Original title, IMDB/Wikipedia links, real-paragraph Wikipedia summary/plot/critical-reception (parser-rendered HTML, sanitized), and a square-grid rating widget
- [ ] **Phase 20: Dashboard/Hub Expansion** - Batch Wiki Reload moves to the dashboard as its own progress + "last 5 processed" card; language/genre stats become spelled-out chip grids
- [ ] **Phase 21: Index CSV Backup & Restore** - Export the full index to CSV (incl. personal fields) and restore/merge it back in, independent of TMDB matching
- [ ] **Phase 22: Personal Lists Foundation** - Mark movies, create/name lists, and manage them (no PDF export yet)

## Phase Details

### Phase 17: Bulk Import UX Polish

**Goal**: Users get real-time, information-dense visibility into a running or completed bulk import without leaving the page they started from.
**Depends on**: Nothing new — builds on the shipped Bulk Import feature (Phases 10, 11, 15, 16)
**Requirements**: IMPORT-11, IMPORT-12, IMPORT-13
**Success Criteria** (what must be TRUE):

  1. On the Add Film page, a live progress bar for a running bulk import appears directly on that page (no navigation required), with a link to the existing batch detail page — IMPORT-12
  2. On the batch detail page, each film's poster pops in live as soon as that individual film finishes processing, instead of only appearing once the whole batch completes — IMPORT-13
  3. The batch detail page offers a no-poster table list-view (real columns: title, year, status, etc.) as an option alongside the existing grid/list views — IMPORT-11

**Plans**: TBD
**UI hint**: yes

### Phase 18: Search UX Polish

**Goal**: Search results are easier to scan and refine — filter values read as words, sort direction is reversible, and a dense table view is available.
**Depends on**: Phase 17 (reuses the no-poster table list-view component built there)
**Requirements**: SRCH-05, SRCH-06, SRCH-07
**Success Criteria** (what must be TRUE):

  1. Language and Production-Country filter values display as full names ("German", "United States") instead of ISO codes, both in the filter panel and on result attributes — SRCH-05
  2. Every sort criterion has a reverse-direction toggle (e.g. Title A→Z / Z→A) — SRCH-06
  3. Search results offer the same no-poster table list-view pattern as Bulk Import (Phase 17) — SRCH-07

**Plans**: TBD
**UI hint**: yes

### Phase 19: Detail Page, Wikipedia Content & Rating Redesign

**Goal**: The movie detail page surfaces the data it already has (original title, IMDB link, Wikipedia link/summary) with genuinely readable Wikipedia content, and the personal rating widget is redesigned.
**Depends on**: Nothing new — independent detail-page and WikipediaClient work; can run in parallel with Phases 17–18
**Requirements**: DETAIL-06, DETAIL-07, DETAIL-08, DETAIL-09, RATE-01, RATE-02, WIKI-01, WIKI-02, WIKI-03
**Success Criteria** (what must be TRUE):

  1. The film's Original Title is displayed on the detail page — DETAIL-06
  2. A clickable IMDB link appears directly under the title, in addition to the existing link next to the rating — DETAIL-07
  3. A clickable Wikipedia link appears once Wikipedia data has loaded — DETAIL-08
  4. The Wikipedia summary, plot, and critical-reception sections render as parser-generated, sanitized HTML with real paragraph structure (multiple readable paragraphs, not one flat text block) — DETAIL-09, WIKI-01, WIKI-02, WIKI-03
  5. The personal rating control is a gapless square grid (no stars) and shows the chosen number (1–10) as visible feedback in or near the selected box — RATE-01, RATE-02

**Plans**: TBD
**UI hint**: yes

### Phase 20: Dashboard/Hub Expansion

**Goal**: The dashboard becomes a real overview — surfacing wiki-reload activity directly and presenting language/genre facets as readable chips instead of a stacked list.
**Depends on**: Phase 17 (reuses the live-progress-bar + link-to-own-detail-page pattern for the new Batch Wiki Reload card)
**Requirements**: HUB-01, HUB-02, HUB-03, HUB-04, HUB-05
**Success Criteria** (what must be TRUE):

  1. The Batch Wiki Reload trigger moves from Settings onto the Dashboard as its own card — HUB-01
  2. That card shows a live progress bar while a reload runs and links to its own detail page — HUB-02
  3. That card also shows a "last 5 processed movies" mini-monitor — HUB-03
  4. Dashboard language stats display as full names in a grid/flexbox chip layout instead of a stacked list — HUB-04
  5. Dashboard genre stats display in the same grid/flexbox chip layout — HUB-05

**Plans**: TBD
**UI hint**: yes

### Phase 21: Index CSV Backup & Restore

**Goal**: Users can back up and restore their entire archive independent of TMDB matching, without losing personal data already on file.
**Depends on**: Nothing — deliberately independent of Bulk Import's CSV pipeline (different purpose: backup/merge vs. TMDB discovery, per REQUIREMENTS.md Out of Scope note)
**Requirements**: SET-05, SET-07
**Success Criteria** (what must be TRUE):

  1. User can export a CSV of the complete index — every field per film, including personal data (rating, notes, watched-status) — SET-05
  2. User can import/restore that CSV directly with no TMDB re-matching; for a film already in the index, empty fields in the imported row fill the corresponding gaps while existing populated fields are left untouched — SET-07

**Plans**: TBD
**UI hint**: yes

### Phase 22: Personal Lists Foundation

**Goal**: Users can organize their collection into custom, named lists (foundation only — PDF export is a v3 candidate).
**Depends on**: Nothing — standalone new feature area (new `list` entity + list-movie join), no dependency on other v2.0 phases
**Requirements**: LIST-01, LIST-02, LIST-03
**Success Criteria** (what must be TRUE):

  1. User can mark movies in the index and add them to a list — LIST-01
  2. User can create and name new lists — LIST-02
  3. User can view and manage their own lists — remove films, rename or delete a list — LIST-03

**Plans**: TBD
**UI hint**: yes

## Progress

| Phase | Milestone | Plans | Status | Completed |
|-------|-----------|-------|--------|-----------|
| 0. Setup & Infrastructure | v1.0 | -/- | Complete | 2026-05-15 |
| 1. Authentication | v1.0 | 3/3 | Complete | 2026-05-16 |
| 2. Settings & API Keys | v1.0 | 3/3 | Complete | 2026-05-16 |
| 3. Save Movie Flow | v1.0 | 6/6 | Complete | 2026-05-17 |
| 4. OpenSearch Indexing | v1.0 | 3/3 | Complete | 2026-05-18 |
| 5. Search | v1.0 | 4/4 | Complete | 2026-05-19 |
| 6. Movie Detail & Personal Fields | v1.0 | 6/6 | Complete | 2026-05-20 |
| 7. Polish & Quality | v1.0 | 3/3 | Complete | 2026-05-21 |
| 8. Wiki Enrichment Tracking & Batch Reload | v1.1 | 2/2 | Complete | 2026-08-23 |
| 9. Manual Wiki Retry | v1.1 | 2/2 | Complete | 2026-08-23 |
| 10. Bulk Import Engine | v1.1 | 3/3 | Complete | 2026-08-24 |
| 11. Bulk Import Feedback UI | v1.1 | 5/5 | Complete | 2026-08-25 |
| 12. Wikidata-based Wikipedia Lookup | v1.1 | 1/1 | Complete | 2026-08-27 |
| 13. Wikidata SPARQL Batch Lookup | v1.1 | 3/3 | Complete | 2026-08-27 |
| 14. Wiki Batch-Reload Pacing, Cooldown-Fix & Progress UI | v1.1 | 2/2 | Complete | 2026-08-28 |
| 15. Bulk Import Page Completion | v1.1 | 6/6 | Complete | 2026-08-28 |
| 16. Bulk Import Correctness & Wiki-Reload Progress Clarity | v1.1 | 4/4 | Complete | 2026-08-29 |
| 17. Bulk Import UX Polish | v2.0 | 0/TBD | Not started | - |
| 18. Search UX Polish | v2.0 | 0/TBD | Not started | - |
| 19. Detail Page, Wikipedia Content & Rating Redesign | v2.0 | 0/TBD | Not started | - |
| 20. Dashboard/Hub Expansion | v2.0 | 0/TBD | Not started | - |
| 21. Index CSV Backup & Restore | v2.0 | 0/TBD | Not started | - |
| 22. Personal Lists Foundation | v2.0 | 0/TBD | Not started | - |
