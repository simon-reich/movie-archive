---
status: testing
phase: 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
source: [15-VERIFICATION.md]
started: 2026-08-28T14:25:58Z
updated: 2026-08-28T14:25:58Z
---

## Current Test

number: 1
name: Toggle to list view on /imports/{batchId}, do a real hard browser reload, confirm list view is still selected.
expected: |
  View mode persists across a genuine full-page reload, not just a re-mounted component with pre-seeded localStorage.
awaiting: user response

## Tests

### 1. List view persists across a real hard browser reload (D-02)
expected: View mode persists across a genuine full-page reload, not just a re-mounted component with pre-seeded localStorage.
result: [pending]

### 2. SAVED-card navigation + PARSE_ERROR visual distinctiveness in a real browser
expected: Open a real bulk-import batch with mixed statuses (SAVED/AMBIGUOUS/NOT_FOUND/PARSE_ERROR). Click a SAVED card → navigates to /movies/{id}. PARSE_ERROR reads as a clearly distinct category (not just another status icon).
result: [pending]

### 3. End-to-end inline resolve against the real TMDB API
expected: Expand the resolve widget on a real AMBIGUOUS or NOT_FOUND line, run a live TMDB search, pick a candidate, and confirm the batch report immediately shows SAVED with a working movie link.
result: [pending]

### 4. Real-world regression import of saubere_filmliste.txt (D-17)
expected: Run a real bulk import against saubere_filmliste.txt (repo root, untracked, 1139 lines, semicolon format) using the live app stack (TMDB key, DB, SSE progress) and confirm every line resolves to the identical per-line outcome it would have produced before this phase — a no-op regression check.
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps
