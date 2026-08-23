---
created: 2026-08-23T12:27:53.028Z
title: Add batch wiki-reload trigger button to UI
area: ui
severity: minor
resolves_phase: 9
files:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
---

## Problem

Phase 8 built the full batch-reload mechanism (`POST /admin/wiki-reload/{userId}` — async, paced, cooldown-filtered) but deliberately shipped it as admin-endpoint-only with no UI trigger (see `.planning/phases/08-wiki-enrichment-tracking-batch-reload/08-CONTEXT.md` D-06). During a follow-up conversation (2026-08-23) the user clarified this should in fact be user-triggerable, not admin-only/hidden — they want a way to fire the batch reload themselves without curl.

## Solution

Manual trigger only — no scheduled/automatic background triggering (explicitly decided against, consistent with Phase 8's CONTEXT.md rejection of a scheduler). Add a "Reload missing Wikipedia data" button, likely on the Settings page, that calls the existing `POST /admin/wiki-reload/{userId}` endpoint via fetch and shows success/error feedback (e.g. a toast). No new backend work needed — this is UI-only.

User decision 2026-08-23: fold this into Phase 9 alongside the existing per-film manual retry button (ENRICH-04/05) rather than spin up a separate phase, since the underlying mechanism is proven and this is considered minimal additional effort.
