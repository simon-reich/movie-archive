# Roadmap: MovieArchive

## Overview

MovieArchive delivers a personal searchable film archive in seven phases. Authentication gates everything — it ships first. Settings (API keys) unlock the save flow. The save flow populates OpenSearch, which powers search. Movie detail and personal fields complete the UX. E2E tests and mobile polish close out v1. Phase 0 (repo setup, Docker Compose, skeletons, CI) is complete.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 0: Setup & Infrastructure** - Repo, Docker Compose, Spring Boot + Nuxt skeletons, CI (COMPLETE)
- [ ] **Phase 1: Authentication** - Sign-up, email verification, login, logout, token rotation, password reset (IN PROGRESS)
- [ ] **Phase 2: Settings & API Keys** - TMDB/OMDB key management, password change, email change, CSV export/import
- [ ] **Phase 3: Save Movie Flow** - Async TMDB → OMDB → Wikipedia → Postgres enrichment pipeline with status feedback
- [ ] **Phase 4: OpenSearch Indexing** - Index lifecycle, custom analyzer, field mappings, admin reindex endpoint
- [ ] **Phase 5: Search** - Full-text search, advanced filters, sorting, clickable attribute navigation
- [ ] **Phase 6: Movie Detail & Personal Fields** - Detail page with all metadata, watched status, rating, notes, trailer
- [ ] **Phase 7: Polish & Quality** - Responsive/mobile, E2E Playwright tests, README

## Phase Details

<details>
<summary>Phase 0: Setup & Infrastructure — COMPLETE</summary>

### Phase 0: Setup & Infrastructure
**Goal**: Working mono-repo with runnable skeletons for both services and a passing CI pipeline
**Depends on**: Nothing (first phase)
**Requirements**: (pre-requirements; captured in PROJECT.md Validated section)
**Success Criteria** (what must be TRUE):
  1. `docker compose up` starts all services (Caddy, Spring Boot, Nuxt, PostgreSQL, Mailpit)
  2. Spring Boot health endpoint returns 200
  3. Nuxt skeleton renders a page in browser
  4. GitHub Actions CI pipeline runs and passes on push
**Plans**: Complete (MOV-1 through MOV-20 includes Phase 0 + partial Phase 1)
**Status**: COMPLETE — 2026-05-15

</details>

### Phase 1: Authentication
**Goal**: Users can create accounts, verify email, log in securely, and recover forgotten passwords
**Depends on**: Phase 0 (complete)
**Requirements**: AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06, AUTH-07, AUTH-08
**Success Criteria** (what must be TRUE):
  1. User can register with email + password and receives a verification email
  2. Account is only active after clicking the email verification link
  3. User can log in with email + password and receives JWT access token (15 min) + HttpOnly refresh cookie (7 days)
  4. User stays logged in across browser sessions via token refresh; refresh token is rotated on each use
  5. User can log out and the refresh token is immediately revoked
  6. User can request a password reset email and reset password via single-use token link; all existing sessions are invalidated
**Plans**: TBD
**UI hint**: yes

### Phase 2: Settings & API Keys
**Goal**: Users can configure API keys and manage their account credentials
**Depends on**: Phase 1
**Requirements**: SET-01, SET-02, SET-03, SET-04, SET-05, SET-06
**Success Criteria** (what must be TRUE):
  1. User can save and update a TMDB API key (stored AES-256-GCM encrypted, displayed masked)
  2. User can optionally save and update an OMDB API key with same encryption behavior
  3. User can change password by providing current password; all existing sessions are invalidated
  4. User can change email address; a verification link is sent to the new address and the old address is notified
  5. User can export all their movie data as a CSV file
  6. User can import movie data from a CSV file
**Plans**: TBD
**UI hint**: yes

### Phase 3: Save Movie Flow
**Goal**: Users can add a film to their archive in seconds and see its enrichment status
**Depends on**: Phase 2
**Requirements**: SAVE-01, SAVE-02, SAVE-03, SAVE-04, SAVE-05
**Success Criteria** (what must be TRUE):
  1. User can search TMDB or enter a TMDB ID to add a film; the action returns immediately (202 Accepted)
  2. Film appears in the archive after async enrichment completes (TMDB → OMDB optional → Wikipedia → Postgres → OpenSearch)
  3. OMDB enrichment is silently skipped when no OMDB key is configured or when TMDB response lacks an imdb_id
  4. Wikipedia enrichment failure (including exhausted 6-step fallback) does not prevent the film from being saved
  5. UI displays save status visibly: pending → success or error — no silent failures
**Plans**: TBD
**UI hint**: yes

### Phase 4: OpenSearch Indexing
**Goal**: Every saved film is indexed with a production-ready custom analyzer so search works correctly
**Depends on**: Phase 3
**Requirements**: IDX-01, IDX-02, IDX-03, IDX-04
**Success Criteria** (what must be TRUE):
  1. Every film persisted to Postgres is indexed into `movies-{userId}` in OpenSearch
  2. The index is auto-created with custom analyzer (asciifolding, lowercase, elision, stop_english, kstem) and all field mappings on first write — no manual setup required
  3. Admin can trigger a full index rebuild for a user via `POST /admin/reindex/{userId}`
**Plans**: TBD

### Phase 5: Search
**Goal**: Users can find any film in their archive using text or structured filters
**Depends on**: Phase 4
**Requirements**: SRCH-01, SRCH-02, SRCH-03, SRCH-04
**Success Criteria** (what must be TRUE):
  1. User can type a free-text query and get relevant results across all indexed film fields
  2. User can combine advanced filters: genre, director, year, IMDB rating, content rating, watched status
  3. User can sort results by title A–Z, release year, personal rating, or IMDB rating
  4. Clicking an actor, director, or genre on any page opens a pre-filtered search results list
**Plans**: TBD
**UI hint**: yes

### Phase 6: Movie Detail & Personal Fields
**Goal**: Users can view complete film metadata and record their personal relationship to each film
**Depends on**: Phase 3
**Requirements**: DETAIL-01, DETAIL-02, DETAIL-03, DETAIL-04, DETAIL-05
**Success Criteria** (what must be TRUE):
  1. Film detail page shows all TMDB and OMDB fields; nullable OMDB fields are hidden when absent
  2. Film detail page shows Wikipedia plot and critics sections when available
  3. User can set watched status, personal rating (0–10), and free-text notes on any film; notes are indexed for search
  4. Film detail page shows YouTube trailer embed when a trailer key is available via TMDB
  5. Clicking an actor, director, or genre on the detail page opens a filtered search results list
**Plans**: TBD
**UI hint**: yes

### Phase 7: Polish & Quality
**Goal**: The app works well on mobile and is covered by E2E tests with a clear README for setup
**Depends on**: Phase 5, Phase 6
**Requirements**: QLTY-01, QLTY-02, QLTY-03
**Success Criteria** (what must be TRUE):
  1. All core flows (auth, save, search, detail) are fully usable on a mobile device
  2. Playwright E2E tests cover the happy path: sign-up → verify email → save film → search → view detail
  3. README documents local setup steps and feature overview clearly enough to run the project from scratch
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 0. Setup & Infrastructure | -/- | Complete | 2026-05-15 |
| 1. Authentication | 0/TBD | In progress | - |
| 2. Settings & API Keys | 0/TBD | Not started | - |
| 3. Save Movie Flow | 0/TBD | Not started | - |
| 4. OpenSearch Indexing | 0/TBD | Not started | - |
| 5. Search | 0/TBD | Not started | - |
| 6. Movie Detail & Personal Fields | 0/TBD | Not started | - |
| 7. Polish & Quality | 0/TBD | Not started | - |
