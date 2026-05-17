---
status: partial
phase: 05-search
source: [05-VERIFICATION.md]
started: 2026-05-17T23:45:00Z
updated: 2026-05-17T23:45:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Free-text search debounce
expected: Typing a query triggers a search ~300ms after the last keystroke (not on every keystroke). Results update in the result grid/list without a full page reload.
result: [pending]

### 2. Advanced filter combination
expected: Selecting genre + year range + IMDB rating minimum together narrows results correctly. Filters are combined (AND logic), not OR.
result: [pending]

### 3. Genre chip navigation
expected: Clicking a genre chip on a MovieCard navigates to /search with `?genre=<Genre>` pre-filled and results already filtered.
result: [pending]

### 4. Sort ordering
expected: Switching sort from "Title A–Z" to "IMDB Rating" reorders results descending by IMDB rating. Films without a rating appear last.
result: [pending]

### 5. View toggle localStorage persistence
expected: Switching between Grid and List view, then reloading the page, restores the previously selected view mode.
result: [pending]

### 6. Dashboard with real data
expected: Dashboard shows accurate stats (total films, genres, etc.), a movie-of-the-day that stays the same on the same calendar day, and a recently-added list.
result: [pending]

### 7. Empty archive empty-state CTA
expected: When no films are indexed, the search page shows an empty state with a "Save your first film" call-to-action rather than an empty grid/list.
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
