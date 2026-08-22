---
status: complete
phase: 03-save-movie-flow
source: [03-01-SUMMARY.md, 03-02-SUMMARY.md, 03-03-SUMMARY.md, 03-04-SUMMARY.md, 03-05-SUMMARY.md]
started: 2026-05-17T00:00:00Z
updated: 2026-05-17T00:00:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Kill any running server/service. Start the application from scratch (docker-compose up or equivalent). Backend boots without errors, Flyway applies V6 migration (movies table created), and the app is reachable.
result: pass

### 2. AppNav "Add Film" link
expected: After logging in, the top navigation bar contains an "Add Film" link. Clicking it navigates to /add.
result: pass

### 3. TMDB search returns results
expected: On the /add page, typing a movie title in the search bar (e.g., "Inception") and submitting shows a grid of movie poster cards with title, year, and poster image.
result: pass

### 4. Search without TMDB key shows error
expected: With no TMDB API key configured in Settings, searching on /add returns a 422 response and the UI shows an error message (e.g., "No TMDB key configured" or similar).
result: pass

### 5. Poster click initiates save with spinner
expected: Clicking a movie poster on /add triggers a save. The clicked poster immediately shows a spinner/loading indicator while enrichment runs in the background (202 Accepted).
result: pass

### 6. Successful enrichment shows success state
expected: After the backend completes enrichment (TMDB → OMDB → Wikipedia → Postgres), the poster's spinner is replaced by a success indicator (green checkmark or "Saved" label). No page reload required — polling updates the UI automatically.
result: pass

### 7. Duplicate save is idempotent
expected: Clicking the same movie poster a second time (or searching the same film and clicking it again) does not create a duplicate. The UI shows the existing saved state without an error.
result: issue
reported: "Es scheint ein Duplikat angelegt zu werden, zumindest ist der Vorgang identisch zum ersten Speichern: Spinner und dann ein success und das Poster verschwindet aus dem Grid."
severity: major

### 8. API key delete in Settings
expected: On the Settings page, each configured API key (TMDB, OMDB) has a Delete button. Clicking it removes the key — subsequent searches require re-entering the key.
result: pass

## Summary

total: 8
passed: 7
issues: 1
pending: 0
skipped: 0

## Gaps

- truth: "Clicking the same movie poster a second time does not create a duplicate — UI shows existing saved state"
  status: failed
  reason: "User reported: Vorgang identisch zum ersten Speichern — Spinner, dann Success, Poster verschwindet aus Grid"
  severity: major
  test: 7
  artifacts:
    - frontend/pages/add.vue (handlePosterClick line 39 — guard only covers same-session in-flight saves)
    - backend/src/main/java/de/moviearchive/movie/MovieController.java (line 37 — enrich() called unconditionally)
    - backend/src/main/java/de/moviearchive/movie/MovieService.java (initiate() returns UUID only, no new-vs-existing signal)
  missing:
    - Frontend needs to fetch already-saved tmdbIds on page load and mark those posters as already saved
    - MovieService.initiate() needs to return a signal (e.g. boolean isNew or a wrapper) so the controller can skip re-enrichment for existing movies
    - MovieController must only call enrichmentService.enrich() when the movie is newly created
