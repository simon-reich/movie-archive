# Phase 15: Bulk Import Page Completion: View Toggle, Movie Links, Real CSV Parsing - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-28
**Phase:** 15-bulk-import-page-completion-view-toggle-movie-links-real-csv
**Areas discussed:** View toggle, Movie links for SAVED lines, Inline ambiguous/not-found resolution, Real CSV parsing & format

---

## View toggle

| Question | Options | Selected |
|---|---|---|
| Default view mode | Grid (current) / List / Remember last used | **Grid (current)** |
| Where does the preference persist | localStorage / Not persisted | **localStorage** |
| Does list view show posters | Text-only list / Small thumbnail + text | **Small thumbnail + text** |
| Status visibility in list view | Yes, same status vocabulary / You decide | **Yes, same status vocabulary** |

**Notes:** Reuses the existing `CheckCircle2`/`XCircle` icon + `statusLabel()` mapping from `frontend/pages/imports/[batchId].vue`, laid out inline per row instead of as an image overlay.

---

## Movie links for SAVED lines

| Question | Options | Selected |
|---|---|---|
| Card click target | Whole card clickable / Small "View" link/icon only | **Whole card clickable** |
| How movieId is resolved | Look up at response time via `MovieRepository.findByUserIdAndTmdbId()` / Store movieId on BulkImportLine (migration) | **Look up at response time** |
| Link for AMBIGUOUS/NOT_FOUND/PARSE_ERROR cards | No link — handled by inline resolve / Link to prefilled Add Film search | **No link — handled by inline resolve** |

**Notes:** No schema change — `movieId` is resolved server-side in `BulkImportController.getBatchDetail()` using the already-existing `findByUserIdAndTmdbId` query.

---

## Inline ambiguous/not-found resolution

| Question | Options | Selected |
|---|---|---|
| Candidate source | Fresh TMDB search on expand / Re-run bulk-import's exact matcher server-side | **Fresh TMDB search on expand** |
| Card update after save | Refetch full batch detail / Optimistic local patch | **Refetch full batch detail** |
| Should resolve update the BulkImportLine row | Yes — new endpoint links save to the line / No — plain save, line stays AMBIGUOUS forever | **Yes — new endpoint links save to the line** |
| PARSE_ERROR gets same widget? | Yes, same widget for all 3 / No, PARSE_ERROR is different | **No, PARSE_ERROR is different** |

**User's choice (freeform, German):** "Path Error sollte vielleicht eine eigene Kategorie werden." — asked to confirm intent; user clarified: "Ja genau, wie eins. Und dann kann man vielleicht einfach den CSV-String da reinsetzen, sodass man dass man irgendwie nachvollziehen kann, wo der Fehler möglicherweise liegt in der Datei."

**Notes:** PARSE_ERROR is treated as a distinct visual category (not just another failure badge) with no inline resolve widget, and displays the raw line text (`BulkImportLine.rawLine`) for traceability. No stored TMDB candidates exist for AMBIGUOUS lines (confirmed in `BulkImportService.java` — "D-04: multiple candidates, no unambiguous narrowing — never auto-guess") — candidates are discarded, so inline resolve must re-search fresh.

---

## Real CSV parsing & format

| Question | Options | Selected |
|---|---|---|
| Is `saubere_filmliste.txt` the real test input | Yes, that's my real list / No, unrelated | **Yes, that's my real list** (no special handling needed — can be converted later if needed) |
| Replace or support both formats | Support both / Replace with comma-CSV only | **Support both** |
| Optional header row support | Yes, auto-detect header row / No header support | **Yes, auto-detect header row** |
| CSV library | Apache Commons CSV / Hand-rolled parser | **Apache Commons CSV** |
| Title containing a comma | (clarifying question) | Handled natively by CSV quoting once Commons CSV is in place — no special-casing needed |
| Title containing a semicolon (legacy format) | Accept as known limitation / Add quoting to semicolon format too | **Accept it as a known limitation** |

**Notes:** Legacy semicolon parser (`ImportLineParser`) stays unchanged — no quoting added there. A semicolon-in-title edge case in the old format will surface as PARSE_ERROR, now diagnosable via the raw-line display decided in the Inline resolve area above.

---

## Claude's Discretion

- Exact visual treatment distinguishing PARSE_ERROR cards from AMBIGUOUS/NOT_FOUND (badge color, separate section, distinct styling) — left to implementation as long as it reads as clearly separate.
- Exact CSV delimiter auto-detection strategy (sniff first line vs. try-comma-then-fallback-to-semicolon) — left to research/planning.

## Deferred Ideas

- CSV export (other half of the source todo) — tracked separately as v2-candidate SET-05, not this phase.
- CSV import as a structured multi-field format (more columns) — excluded from v1.1 per REQUIREMENTS.md, stays v2-candidate SET-06. This phase only changes delimiter/quoting within the existing 3-column schema.
- `.planning/todos/pending/2026-08-27-authorizationdeniedexception-on-sse-emitter-complete.md` — wiki-reload SSE completion bug, not bulk-import; reviewed but not folded.
- `.planning/todos/pending/2026-08-27-distinguish-stopped-vs-completed-in-progress-ui.md` — wiki-reload progress UI, not bulk-import; reviewed but not folded.
- `.planning/todos/pending/2026-08-28-create-api-contract-doc-for-future-flutter-port.md` — project-wide doc todo, not phase-specific; reviewed but not folded.
