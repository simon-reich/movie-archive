---
created: 2026-08-25T06:19:46.419Z
title: Enhance bulk import batch detail page: view toggle, movie links, inline ambiguous resolve
area: bulk-import
severity: minor
files:
  - frontend/pages/imports/[batchId].vue
---

## Problem

Phase 11 shipped the bulk-import batch detail page (`/imports/{batchId}`) showing a flat per-line results list (title, poster or fallback, status). Follow-up usability gaps surfaced during Phase 11 UAT that weren't in scope for that phase:

A) There's no way to switch between a compact list view (no posters) and a poster grid view — useful for quickly scanning many lines vs. visually verifying posters.

B) A SAVED line isn't clickable — the user can't jump from the batch results straight to that movie's detail page to confirm it's actually the right film (poster/title alone isn't always enough certainty).

C) AMBIGUOUS and NOT_FOUND lines are dead ends on this page — the user has to leave and use the separate manual-search/retry flow, then has no way to see, back on the batch page, that a given line has since been resolved. There's no inline search-and-load action on the line itself, and no live update of the line's status after a manual resolve.

## Solution

TBD — likely:
- (A) a local view-mode toggle (list/grid) on the batch detail page, probably persisted per-user in browser storage rather than a backend setting
- (B) wrap/link SAVED line cards to the movie's detail page (needs the movie id, which may require joining bulk_import_line to movie or exposing it in the batch-detail response — check what `GET /movies/bulk-import/batches/{batchId}` currently returns)
- (C) reuse/extend the existing manual per-film retry search (see Phase 9 "Manual Wiki Retry" and any bulk-import ambiguous-resolution endpoint) as an inline widget on the AMBIGUOUS/NOT_FOUND line card, and refresh that line's status in the UI (poll or re-fetch) once resolved — same "no auto-guessing, always manual confirmation" principle as the rest of bulk import

Candidate for a dedicated follow-up phase after Phase 12, since it touches both the batch-detail API response shape and new frontend interaction — not a quick fix.
