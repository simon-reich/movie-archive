# Phase 10: Bulk Import Engine - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-23
**Phase:** 10-bulk-import-engine
**Areas discussed:** Line format & parsing rules, TMDB matching & ambiguity criteria, Already-imported dedup mechanism, Job execution & result persistence model

---

## Line format & parsing rules

| Option | Description | Selected |
|--------|-------------|----------|
| "Title (Original Title) Year" — parens optional | Matches ROADMAP wording exactly | |
| CSV-style: Title;OriginalTitle;Year | More rigid/explicit, doesn't match ROADMAP wording | ✓ |
| You decide | Claude picks during planning | |

**User's choice:** CSV-style `Title;OriginalTitle;Year`
**Notes:** Deliberate deviation from the ROADMAP.md parenthetical wording — user picked the more explicit delimited format when given the choice.

| Option | Description | Selected |
|--------|-------------|----------|
| Trim + skip blank lines silently, UTF-8 only | Avoids spurious parse errors from formatting | ✓ |
| Strict — any blank line or stray whitespace is a parse error | Flags benign formatting as errors | |
| You decide | | |

**User's choice:** Trim + skip blank lines silently, UTF-8 only

| Option | Description | Selected |
|--------|-------------|----------|
| Record as "parse error" and continue to next line | Matches IMPORT-06 status vocabulary | ✓ |
| Abort the entire import on first bad line | Harsh — one typo blocks everything | |
| You decide | | |

**User's choice:** Record as "parse error" and continue

| Option | Description | Selected |
|--------|-------------|----------|
| Used to help disambiguate TMDB candidates in this phase | More useful, adds matching logic now | ✓ |
| Captured but not used for matching yet | Simpler for Phase 10 | |
| You decide | | |

**User's choice:** Used for matching in this phase (see D-06 in CONTEXT.md)

---

## TMDB matching & ambiguity criteria

| Option | Description | Selected |
|--------|-------------|----------|
| Exact year match only | Matches ROADMAP wording precisely | ✓ |
| ±1 year tolerance | Handles region/festival date discrepancies, loosens ambiguity criteria | |
| You decide | | |

**User's choice:** Exact year match only

| Option | Description | Selected |
|--------|-------------|----------|
| Exact match (case-insensitive) on original_title narrows to one → auto-save | Simple, deterministic | ✓ |
| Fuzzy/partial match | More forgiving but introduces false-positive risk | |
| You decide | | |

**User's choice:** Exact case-insensitive match

| Option | Description | Selected |
|--------|-------------|----------|
| Record as "not found" and continue | Matches IMPORT-06 status vocabulary | ✓ |
| Treat as ambiguous | Conflates two different failure modes | |
| You decide | | |

**User's choice:** Record as "not found" and continue

---

## Already-imported dedup mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Normalized (title, year) pair checked against a persisted import record | Enables true no-TMDB-call skip on re-upload | ✓ |
| Cache raw TMDB search results per (title, year) | Still calls TMDB the first time, conflates caching with dedup | |
| You decide | | |

**User's choice:** Normalized (title, year) pair against persisted import record

| Option | Description | Selected |
|--------|-------------|----------|
| New table bulk_import_line (user_id, title, year, tmdb_id, status) | Feeds Phase 11's results UI directly | ✓ |
| Reuse existing Movie table only | Can't distinguish "tried, zero matches" from "never tried" | |
| You decide | | |

**User's choice:** New `bulk_import_line` table

| Option | Description | Selected |
|--------|-------------|----------|
| Only skip lines with status "saved" — retry ambiguous/not-found every time | Matches IMPORT-07's literal wording | ✓ |
| Skip every line ever processed regardless of outcome | Traps fixable typos/not-yet-on-TMDB titles permanently | |
| You decide | | |

**User's choice:** Only skip status "saved"; retry ambiguous/not-found/parse-error lines every re-upload

---

## Job execution & result persistence model

| Option | Description | Selected |
|--------|-------------|----------|
| Async — 202 Accepted, background job via dedicated bounded executor | Mirrors WikiReloadService pattern, needed for Phase 11 progress | ✓ |
| Synchronous — request blocks until whole file processed | Risks timeout on large files, would need rework for Phase 11 anyway | |

**User's choice:** Async, mirroring WikiReloadService

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse MovieService.initiate() + enrichmentService.enrich() as-is | Matches IMPORT-03's explicit instruction | ✓ |
| Build a bulk-specific save variant | Deviates from locked requirement, scope creep | |

**User's choice:** Reuse existing save/enrich path as-is

| Option | Description | Selected |
|--------|-------------|----------|
| Write/update per-line status immediately as each line completes | Gives Phase 11 live progress for free | ✓ |
| Buffer results in memory, write all rows at job completion | Provides nothing for Phase 11's live progress requirement | |

**User's choice:** Write/update per-line status live, as each line completes

---

## Claude's Discretion

- Exact `bulk_import_line` schema beyond the named columns (timestamps, primary key shape, indexes).
- Whether the uploaded file needs header-row / RFC 4180 quoted-CSV support — not raised during discussion; default to plain semicolon-split, no header, no quoting.
- Executor bean naming/pool sizing for the bulk-import background job — follow the `wikiReloadExecutor` sizing pattern unless there's reason to differ.

## Deferred Ideas

- **Live progress indicator during import** — Phase 11 (IMPORT-05).
- **Per-line results overview (title, poster, status)** — Phase 11 (IMPORT-06).
- **Manual resolution UI for ambiguous lines** — not scoped in Phase 10 or 11; flagged for roadmap backlog if wanted later.
- Reviewed but not folded: "Show progress indicator for Wikipedia batch-reload" todo (low match score, about the Wikipedia batch-reload job from Phase 8, not bulk import).
