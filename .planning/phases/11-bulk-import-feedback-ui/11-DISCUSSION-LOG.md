# Phase 11: Bulk Import Feedback UI - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-24
**Phase:** 11-bulk-import-feedback-ui
**Areas discussed:** Progress mechanism, Poster images, Interaction scope, Placement / persistence

---

## Progress mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Polling | Frontend polls a new status endpoint every ~2-3s, counting BulkImportLine rows. Reuses the existing single-movie polling pattern in add.vue. No new dependency. | |
| WebSocket/SSE | Server pushes updates actively. No existing code for this in the project — new dependency, more effort, no polling delay. | ✓ |

**User's choice:** WebSocket/SSE
**Notes:** Research should determine SSE vs. full WebSocket given the one-directional (server→client) nature of progress updates.

---

## Poster images

| Option | Description | Selected |
|--------|-------------|----------|
| Cache at save time | poster_path stored on BulkImportLine when a movie is saved (tmdbId already known at that point) — no extra TMDB calls when rendering the results list. | ✓ |
| Live fetch | Posters fetched via TMDB call at render time — extra API calls, risk at large batch sizes. | |
| No posters | Text only (title, year, status) — simplest, but doesn't fully satisfy IMPORT-06 ("poster falls gefunden"). | |

**User's choice:** Cache at save time

---

## Interaction scope (Ambiguous/Not-Found rows)

| Option | Description | Selected |
|--------|-------------|----------|
| Read-only | Results list only displays status. Manual correction stays on the existing Add Film search flow — no new workflow, smaller phase. | ✓ |
| Actionable | Pick a TMDB candidate and save directly from the results list — larger scope, solves today's real-world friction more directly. | |

**User's choice:** Read-only for Phase 11 (deferred: inline resolution noted as a future-phase idea)

---

## Placement / persistence

| Option | Description | Selected |
|--------|-------------|----------|
| Inline on Add Film page | Results list appears below the bulk import section on add.vue — same place as upload, no page navigation. | |
| Dedicated page | New route, separate from the upload form. | (superseded) |

**User's choice (free text, superseded the two options above):** "Die soll auf einer neuen Seite angezeigt werden, beziehungsweise will ich den report insgesamt auch downloadbar haben als file, Damit das nicht mit zwei, drei Klicks plötzlich alles verloren gegangen ist. Das muss irgendwie gesaved werden. Vielleicht auch gar nicht als File, aber wir müssen das loggen pro Bulk und es muss wieder abrufbar sein. Also ich muss irgendwo die Bulk Imports Reports wiederherstellen können, abrufen und wiederherstellen können und mir einsehen können. Kein Download als File, das ist irgendwie zu fizzelig. Aber ich brauche die Reports gespeichert und wieder visualisierbar."

**Notes:** This expanded the placement question into a persistence requirement — reports must survive navigation/session end and be browsable later, not just shown once during/after the triggering upload. Led to a follow-up question on the data model.

---

## Follow-up: Batch data model

| Option | Description | Selected |
|--------|-------------|----------|
| Batch ID per upload | Every upload gets its own batch identifier; BulkImportLine rows tagged accordingly. New page lists past batches (date, line count, status distribution); clicking one opens its full results list. | ✓ |
| Other | User has a different grouping/identification approach in mind. | |

**User's choice:** Batch ID per upload

---

## Claude's Discretion

- SSE vs. full WebSocket implementation choice — user selected the "WebSocket/SSE" bucket as one option; the specific protocol is left to research/planning to recommend (SSE favored given one-directional progress updates, pending research confirmation).

## Deferred Ideas

- Inline resolution of AMBIGUOUS/NOT_FOUND rows directly from the results view (pick a TMDB candidate, save) — noted as a future-phase candidate, motivated by today's real UAT finding (Predator/Zama/Obsession landed AMBIGUOUS with zero visibility).
- Downloadable/exportable report file (CSV/PDF) — explicitly rejected as the primary mechanism, but flagged as a possible small additive feature later since the data will already be persisted per-batch.
- Two pending todos were reviewed but not folded into this phase's scope: "Show progress indicator for Wikipedia batch-reload" (different feature) and "Support real CSV parsing for bulk import" (file-format concern, not results/feedback UI).
