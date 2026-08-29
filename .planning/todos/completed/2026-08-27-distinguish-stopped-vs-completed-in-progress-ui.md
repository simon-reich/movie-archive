---
created: 2026-08-27T18:10:00.000Z
title: Distinguish "stopped early" from "fully completed" in the wiki-reload progress UI
area: backend/frontend
severity: minor
files:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java
  - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
  - frontend/composables/useSettings.ts
  - frontend/pages/settings.vue
---

## Problem

Flagged as WR-02 in `14-REVIEW.md` (Phase 14 code review, 2026-08-27). `WikiReloadProgressService.complete()`
always constructs `new ProgressState(total, total, true, ...)` regardless of whether the run actually
processed all `total` movies or was halted early via the Stop endpoint. `ProgressState` has no field
indicating "stopped" vs "finished". Combined with `settings.vue`'s
`v-if="wikiProgress && !wikiProgress.complete"` guard (which hides the processed/total counter and
per-movie history list once `complete` is true), a user who stops a run mid-way sees the progress panel
simply vanish with no confirmation of how far it actually got.

## Solution

Add a `stopped` (or `reason`) field to `WikiReloadProgressService.ProgressState`, have `complete()` report
the real last-published `processed` count instead of always reporting `total`, and set the new field from
`progressService.isStopRequested(userId)` (checked before `stopFlags` is cleared). Update the frontend
`WikiReloadProgress` type and the Settings page's `v-if` guard so a stopped run still shows its final
processed/total count and history instead of vanishing.

Deferred out of Phase 14's code-review fix pass because it's a `ProgressState` schema change that touches
every existing equality-based assertion in `WikiReloadProgressServiceTest` — worth doing as a deliberate,
tested follow-up rather than a review-pass patch.
