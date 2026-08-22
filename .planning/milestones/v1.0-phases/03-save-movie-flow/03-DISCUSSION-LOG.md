# Phase 3: Save Movie Flow - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-16
**Phase:** 03-save-movie-flow
**Areas discussed:** 202 Async revisit, Add Film UX, Archive view scope, Status tracking, Phase 3/4 boundary, Error state

---

## 202 Accepted — Revisit

| Option | Description | Selected |
|--------|-------------|----------|
| Keep 202 async | External APIs (TMDB + OMDB + Wikipedia) can chain to 3+ seconds. Async keeps the UI immediately responsive, status feedback shows progress. | ✓ |
| Synchronous — TMDB only | Only TMDB fetch is synchronous, OMDB + Wikipedia still async. | |
| Fully synchronous | Wait for TMDB + OMDB + Wikipedia before returning. No status tracking needed. | |

**User's choice:** Keep 202 async — confirmed after clarification that the 202 approach fully supports the spinner-on-poster UX via polling. User had thought 202 meant "no feedback", but polling gives the same UX as synchronous from the user's perspective.

**Notes:** User described their intended UX: click a poster → spinner on that poster → wait for full enrichment → brief success → poster removed from grid. This UX is achievable with 202 + polling, not just synchronous.

---

## Add Film UX — Where does search happen?

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated /add page | A separate page with a search input and poster grid. | ✓ |
| Modal overlay | '+' button opens a modal with search. | |
| Inline on home/index | Search and archive on the same page. | |

**User's choice:** Dedicated /add page.
**Notes:** Clean separation from the (future) archive/search view.

---

## Add Film UX — How does TMDB search work?

| Option | Description | Selected |
|--------|-------------|----------|
| Submit-and-show grid | User types and hits Search. Results appear as a poster grid. | ✓ |
| Autocomplete as-you-type | Results update live (debounced). More API calls. | |
| TMDB ID input only | No search — user enters exact TMDB ID. | |

**User's choice:** Submit-and-show grid.

---

## Add Film UX — TMDB ID fallback visibility

| Option | Description | Selected |
|--------|-------------|----------|
| Small text link below search | Secondary ID input, hidden by default. | |
| Second tab or toggle | Explicit Search / By TMDB ID tabs. | |
| No ID input — search only | Omit TMDB ID input entirely. | ✓ |

**User's choice:** No ID input — search only.
**Notes:** This deviates from SAVE-01's "or directly by TMDB ID" wording. User consciously dropped this for v1.

---

## Archive / List View

| Option | Description | Selected |
|--------|-------------|----------|
| Poster card grid | Responsive poster grid with title + year. | |
| Compact list | One row per film: thumbnail + title + year + genre. | |
| Minimal — title + year only | Text list, no images. | |
| No archive page in Phase 3 | Archive lives on the future search/index page (Phase 5). | ✓ |

**User's choice:** No archive/list view in Phase 3.
**Notes:** User was explicit: "Eigentlich gibt es diese Seite gar nicht. Ich würde die komplett auf den Index part der app schieben. Dort, wo man dann später die eigene Datenbank durchsucht." Phase 3 is purely about the save flow. The first archive/list experience is Phase 5 Search.

---

## Status Tracking Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Poll GET /movies/{id}/status | Frontend polls every 2-3 seconds until SUCCESS or ERROR. | ✓ |
| Server-Sent Events (SSE) | Backend pushes a single event when enrichment completes. | |
| Optimistic — assume success | Spinner plays fixed duration, then shows success regardless. | |

**User's choice:** Poll GET /movies/{id}/status.
**Notes:** SSE considered and rejected as overkill for a personal single-user app with one-at-a-time saves.

---

## Phase 3 vs Phase 4 Boundary

| Option | Description | Selected |
|--------|-------------|----------|
| Phase 3 includes OpenSearch write | Pipeline calls ensureIndexExists() + index at end. Phase 4 upgrades analyzer. | |
| Phase 3 stops at Postgres only | OpenSearch write is entirely Phase 4. SAVE-02 partially deferred. | ✓ |
| Fold Phase 4 into Phase 3 | Build complete OpenSearch setup in Phase 3. Eliminates Phase 4. | |

**User's choice:** Phase 3 stops at Postgres only.
**Notes:** Clean boundary. OpenSearch write + custom analyzer + field mappings = Phase 4.

---

## Error State on /add

| Option | Description | Selected |
|--------|-------------|----------|
| Error state on the poster | Spinner turns into red X / error icon. Poster stays in grid for retry. | ✓ |
| Toast / banner at top | Inline banner above poster grid. Poster cleared. | |
| Poster disappears, silent error | No user-visible error. Violates SAVE-05. | |

**User's choice:** Error state on the poster.
**Notes:** Poster stays in the search result grid on error — user can retry by clicking again.

---

## Claude's Discretion

- Exact Flyway migration version for the movies table
- Polling interval (2-3 seconds; optional backoff)
- Exact error message wording on the poster
- Backend endpoint naming for TMDB search proxy
- AppNav link styling for /add

## Deferred Ideas

- Direct TMDB ID input — dropped for v1, could return in Phase 6/7
- Archive/list view — Phase 5 scope
- OpenSearch write — Phase 4 scope
- Dedicated retry button on error poster — Phase 6/7 polish
