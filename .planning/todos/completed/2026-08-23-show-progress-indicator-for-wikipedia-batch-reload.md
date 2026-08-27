---
created: 2026-08-23T16:16:57.119Z
title: Show progress indicator for Wikipedia batch-reload
area: ui
severity: minor
files:
  - frontend/pages/settings.vue
  - frontend/composables/useSettings.ts
  - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
---

## Problem

The Settings-page "Reload missing Wikipedia data" button (Phase 9, folded from Phase 8's `WikiReloadService.batchReload`) is deliberately fire-and-forget by design (see `.planning/phases/09-manual-wiki-retry/09-CONTEXT.md`) — no batch progress is tracked or surfaced. The user reported this as confusing while live-testing Phase 9 (2026-08-23): after triggering the batch twice, there was no way to tell it was actually running, how far it had gotten, or when it finished. Compounding this, clicking the per-film Retry button on a movie detail page while a batch is in flight also just shows "no page found" with no hint that a background batch might still be processing that same film.

Note: a separate real bug was found and fixed the same day (commit `dcdf81a`) — `WikipediaClient` was hitting Wikipedia's anonymous-API rate limit almost immediately during any real batch run, causing most lookups to silently fail. That fix is unrelated to this todo; this todo is purely about surfacing progress/status once the batch is actually working correctly.

## Solution

TBD. Options to consider:
- Poll a new lightweight status endpoint (e.g. `GET /admin/wiki-reload/status`) exposing `{running: boolean, processed: number, total: number}` for the current user, backed by an in-memory counter in `WikiReloadService.batchReload`.
- Or an SSE/long-poll stream for live updates instead of polling.
- Frontend: a progress bar or "X / Y processed" text on the Settings page while a batch is in flight; possibly also surface "a batch reload is in progress" on the per-film Retry prompt so a "not found" during an active batch reads less like a dead end.
- Keep it lightweight — this is explicitly NOT meant to become full job-queue infrastructure (CONTEXT.md's existing "no scheduler" decision still stands).
