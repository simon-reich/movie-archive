# Phase 16: Bulk Import Correctness & Wiki-Reload Progress Clarity - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-29
**Phase:** 16-bulk-import-correctness-wiki-reload-progress-clarity
**Areas discussed:** Dedup fix scope, Stopped vs Completed UI, Multi-stage TMDB matching details

---

## Dedup fix scope

| Option | Description | Selected |
|--------|-------------|----------|
| Batch-scope the existingSaved fast-path too | Every batch becomes a fully independent snapshot, consistent with CR-01's fix intent | ✓ |
| Keep existingSaved global | Preserves today's behavior; skip entirely if title/year already SAVED in any batch | |

**User's choice:** Batch-scope it too.

| Option | Description | Selected |
|--------|-------------|----------|
| Re-run full match + initiate() for the new batch | Treat as unseen for the new batch; initiate() is idempotent by tmdbId, no duplicate Movie row | ✓ |
| Keep a cross-batch SAVED lookup to skip the TMDB call, but still write a row for the new batch | Saves a redundant TMDB call, more code paths | |

**User's choice:** Re-run full match + initiate().
**Notes:** None.

---

## Stopped vs Completed UI

| Option | Description | Selected |
|--------|-------------|----------|
| Status text above the progress bar | "Stopped at 12/40" vs "Completed 40/40" wording change only | ✓ |
| Same text, add a distinct badge/icon | Keep wording, add a colored pill | |

**User's choice:** Status text above the progress bar.

| Option | Description | Selected |
|--------|-------------|----------|
| Re-enable button + auto-clear panel/history on next Reload click | Button re-enables on stopped-terminal event; new run clears old panel on click | ✓ |
| Keep last stopped panel visible until new run's first progress event | Avoids blank flash, risks briefly showing stale data | |

**User's choice:** Yes to both (re-enable + auto-clear).
**Notes:** User raised an additional, closely-related gap unprompted: the per-movie history list only ever shows SUCCESS (checkmark) vs everything-else (X), even though the backend already has a third real outcome (NOT_FOUND — no Wikipedia article exists, not an error). User's words: "Ich würde gerne noch sehen können bei dem Laden der Daten... Eine Filme ist auch dann erfolgreich durchlaufen worden, wenn es gar keine Wikipedia-Daten gab. Ich würde gerne in dieser Liste auch sehen können, ob Daten gefunden wurden und gespeichert wurden oder nicht."

| Option | Description | Selected |
|--------|-------------|----------|
| 3 distinct icons/labels (SUCCESS/NOT_FOUND/FAILED) | Thread the real WikiRetryOutcome value through to the frontend | ✓ |
| 2 icons, re-labeled text | Keep checkmark/X, add text suffix distinguishing NOT_FOUND from FAILED | |

**User's choice:** 3 distinct icons/labels.
**Notes:** Folded into this area's decisions (D-09) since it's the same panel/history list already being discussed, not a new capability.

---

## Multi-stage TMDB matching details

| Option | Description | Selected |
|--------|-------------|----------|
| Drop the original-title narrowing tiebreaker | New algorithm is exactly as the user specified 2026-08-29, no extra step | |
| Keep it as a fallback before AMBIGUOUS | Preserve Phase 10 D-06's original-title narrowing after exact title+year narrowing fails | ✓ |

**User's choice:** Keep it as a fallback before AMBIGUOUS.

| Option | Description | Selected |
|--------|-------------|----------|
| Case-insensitive, trimmed, against TMDB's title field only | Matches how the search query itself was keyed | |
| Case-insensitive against title OR originalTitle | Also catches cases where TMDB's display title differs from what the user typed | ✓ |

**User's choice:** Case-insensitive against title OR originalTitle.

| Option | Description | Selected |
|--------|-------------|----------|
| Zero TMDB results → NOT_FOUND | Matches today's behavior for the true no-match case | ✓ |
| Zero TMDB results → AMBIGUOUS | Route to manual review alongside true ambiguous cases | |

**User's choice:** NOT_FOUND.
**Notes:** None.

---

## Claude's Discretion

None — all areas resolved with explicit user choices.

## Deferred Ideas

None — discussion stayed within phase scope.
