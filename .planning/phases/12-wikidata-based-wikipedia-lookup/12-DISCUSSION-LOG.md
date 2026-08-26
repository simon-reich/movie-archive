# Phase 12: Wikidata-based Wikipedia lookup - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-26
**Phase:** 12-wikidata-based-wikipedia-lookup
**Areas discussed:** Pending todos fold-in, rollout scope, backfill, OMDB/Wikipedia overlap, dev visibility of resolution method

---

## Pending Todos Fold-In

| Option | Description | Selected |
|--------|-------------|----------|
| Keins aufnehmen | All 3 matched todos (bulk-import view toggle, CSV parsing, wiki batch-reload progress) stay separate — none fit the Wikidata-lookup scope | ✓ |
| Einzeln durchgehen | Evaluate each todo individually for possible fold-in | |

**User's choice:** Keins aufnehmen (Empfohlen)
**Notes:** All three matched todos are Bulk Import (Phase 8–11) topics; Phase 12 is explicitly independent of Bulk Import per ROADMAP.md.

---

## Rollout Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Überall automatisch | Wikidata-first lookup applies to save flow, manual retry, and batch-reload alike — same client, same method | ✓ |
| Nur für bestimmte Pfade | Exclude or special-case one of the three call sites | |

**User's choice:** Überall automatisch (Empfohlen)
**Notes:** None — straightforward single-client rollout.

---

## Backfill of Previously-Failed Films

| Option | Description | Selected |
|--------|-------------|----------|
| Bestehender Batch-Reload reicht | No new functionality; existing Phase 8 cooldown-based batch-reload will naturally pick up affected films with the new lookup on its next run | |
| Aktiv anstupsen als Teil von Phase 12 | Phase 12 itself triggers a batch-reload for affected films once deployed | |

**User's choice:** Neither — explicitly rejected active backfill ("das machen wir auf keinen Fall")
**Notes:** The ~630 films missing Wikipedia data (from the original bulk-import rate-limiting incident) are intentionally left as real-world test material. The user will manually observe/trigger retries after Phase 12 ships to see the new lookup work in practice, rather than have the phase push a bulk re-enrichment.

---

## OMDB / Wikipedia Overlap

**User's question:** Does OMDB already provide Wikipedia-sourced data (plot, critics)? If so, could the pipeline go OMDB → Wikidata → old fallback, skipping Wikipedia entirely when OMDB already has data? Also asked for a stat on how many currently-saved films already have OMDB enrichment.

**Investigation:** Read `.claude/api-contracts.md`. OMDB's `Plot` field is IMDb-sourced (not Wikipedia), and per the existing field-mapping table OMDB is never mapped to `wiki_plot`/`wiki_summary`/`wiki_critics` — only to `content_rating`, `director_list`, `writer_list`, `main_cast`, `rating_list`, `imdb_rating`, `imdb_votes`, `box_office`. The two data sources are complementary, not substitutable.

Could not pull the live DB stat on OMDB coverage — Docker/OrbStack was not running in this session (`docker ps` failed: no daemon socket).

| Option | Description | Selected |
|--------|-------------|----------|
| Passt, weiter ohne Statistik | Accept OMDB/Wikipedia are complementary; no pipeline redesign; proceed without the DB stat | ✓ |
| Erst DB-Statistik prüfen | Get the OMDB-coverage number before finalizing | |

**User's choice:** Passt, weiter ohne Statistik (Empfohlen)
**Notes:** Confirmed no pipeline reordering needed (see CONTEXT.md D-02).

---

## Dev Visibility of Resolution Method (Wikidata vs. Fallback)

**User's motivation:** Recurring frustration with zero visibility into what the Wikipedia enrichment step does — no progress indicator, no way to tell what happened. Wants something to see whether Wikidata resolved a lookup or the old fallback cascade had to run, for analytical/debugging purposes, possibly removed again later.

**Iteration 1 — mechanism:**
| Option | Description | Selected |
|--------|-------------|----------|
| Strukturiertes Log | A clear log line per attempt in Docker/app logs, no DB field, no UI | |
| Sichtbar in der App | A field/badge in the app itself | ✓ (initially) |

**Iteration 2 — placement (multi-select offered):** Film detail page / Batch-reload overview / Admin-debug endpoint (JSON). User reconsidered mid-answer and reversed course: rejected all three in-app options, rejected JSON, rejected plain terminal/Docker log output.

**Iteration 3 — clarified final ask:** A separate, temporary, plain-text file — not the normal app log, not JSON, not terminal output — with one human-readable line per Wikipedia enrichment attempt (e.g. `"Inception (2010): gefunden über Wikidata"` / `"Inception (2010): Fallback-Kandidat #3 (...)"`). Confirmed explicitly as correct via a direct restatement.

**User's final choice:** Separate human-readable temporary text file, one line per attempt, explicitly dev-only and meant to be removable later without residue (see CONTEXT.md D-05).
**Notes:** Exact file path/naming left to planning (Claude's Discretion).

---

## Claude's Discretion

- Exact Wikidata query mechanism (SPARQL vs. `wbgetentities`/`wbsearchentities` REST vs. Special:EntityData)
- Exact file path/name and rotation behavior of the temporary resolution log
- Whether the Wikidata call reuses `WikipediaClient`'s existing pacing/backoff machinery or needs its own

## Deferred Ideas

- Active backfill/re-enrichment trigger for the ~630 affected films — explicitly rejected for this phase, left for organic manual observation instead.
- `2026-08-25-enhance-bulk-import-batch-detail-page-view-toggle-movie-link` — Bulk Import UI, unrelated.
- `2026-08-24-support-real-csv-parsing-for-bulk-import` — Bulk Import parsing, unrelated.
- `2026-08-23-show-progress-indicator-for-wikipedia-batch-reload` — Batch-Reload UI progress indicator; conceptually adjacent to the dev-visibility ask here but scoped as a permanent UI feature for Phase 8's batch-reload, not this phase's temporary dev-log. Revisit after Phase 12 ships.
