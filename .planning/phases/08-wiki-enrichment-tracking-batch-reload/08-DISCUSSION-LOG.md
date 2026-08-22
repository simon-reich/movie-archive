# Phase 8: Wiki Enrichment Tracking & Batch Reload - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-22
**Phase:** 8-wiki-enrichment-tracking-batch-reload
**Areas discussed:** Retry scope & re-indexing, Cooldown window value, Trigger & execution model, Pacing delay between calls

---

## Retry scope & re-indexing

| Option | Description | Selected |
|--------|-------------|----------|
| Wikipedia-only retry (Recommended) | New lean method e.g. retryWikipedia(movieId) — reuses WikipediaClient, leaves TMDB/OMDB data and status untouched. | ✓ |
| Full re-enrichment | Reuse EnrichmentService.enrich() end-to-end — re-fetches TMDB + OMDB too. | |

**User's choice:** Wikipedia-only retry (Recommended)
**Notes:** Matches the actual failure mode — only Wikipedia was rate-limited during the incident.

| Option | Description | Selected |
|--------|-------------|----------|
| Re-index on success (Recommended) | After a successful late wiki fetch, call IndexingService to update the OpenSearch doc and set indexed_at. | ✓ |
| Skip re-indexing | Wiki fields save to Postgres only; search won't find these films until a manual reindex. | |

**User's choice:** Re-index on success (Recommended)
**Notes:** wiki fields are indexed OpenSearch fields (SRCH-01) — without re-indexing they'd be invisible to search even though the detail page (Postgres) would show them.

---

## Cooldown window value

| Option | Description | Selected |
|--------|-------------|----------|
| 30 days (Recommended) | Matches the value already mentioned in PROJECT.md as the working example. | ✓ |
| 7 days | Shorter window — riskier for re-triggering rate limiting if run frequently. | |
| Other | Specify a different number of days. | |

**User's choice:** 30 days (Recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Configurable via property (Recommended) | e.g. wiki.retry.cooldown-days=30 in application.properties, overridable via ENV. | ✓ |
| Hardcoded constant | Single Java constant; requires code change + redeploy to tune. | |

**User's choice:** Configurable via property (Recommended)

---

## Trigger & execution model

| Option | Description | Selected |
|--------|-------------|----------|
| Fire-and-forget async (Recommended) | Endpoint returns immediately; batch job runs on a bounded background thread pool with the pacing delay. No live progress UI in this phase. | ✓ |
| Synchronous blocking | Same pattern as POST /admin/reindex/{userId} — blocks until done. Risks HTTP timeout with hundreds of films × delay. | |

**User's choice:** Fire-and-forget async (Recommended)
**Notes:** Hundreds of films × 1s+ pacing delay could mean 10+ minutes — too long to block an HTTP request.

| Option | Description | Selected |
|--------|-------------|----------|
| Admin endpoint only (Recommended) | POST /admin/wiki-reload/{userId} — same style as existing /admin/reindex/{userId}. No dedicated UI button yet. | ✓ |
| Scheduled/automatic | A cron-like job runs batch-reload periodically. Adds always-on background load; not requested by any ENRICH-0x requirement. | |

**User's choice:** Admin endpoint only (Recommended)

---

## Pacing delay between calls

| Option | Description | Selected |
|--------|-------------|----------|
| 1 second (Recommended) | Conservative but not glacial — 630 films would take ~10.5 minutes. | ✓ |
| 2–3 seconds | More conservative, lower re-trigger risk, but a full 630-film run takes 20-30+ minutes. | |
| Other | Specify a different delay. | |

**User's choice:** 1 second (Recommended)
**Notes:** This is the root-cause knob from the original incident (~89% of 630 films silent-failed from rate limiting).

| Option | Description | Selected |
|--------|-------------|----------|
| Configurable via property (Recommended) | e.g. wiki.retry.pacing-delay-ms=1000 — consistent with the cooldown-days property. | ✓ |
| Hardcoded constant | Fixed in code alongside the batch-reload logic. | |

**User's choice:** Configurable via property (Recommended)

---

## Claude's Discretion

- Exact naming of the new property keys (`wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` are suggestions, not locked).
- Exact naming of the new service method(s) and repository query for "films missing wiki data outside cooldown".
- Whether the sequential pacing loop uses `Thread.sleep` inside the `@Async` batch method or a scheduled-delay mechanism.
- Exact Flyway migration version number for the new `wiki_last_attempted_at` column.
- Confirmed "missing Wikipedia data" = `wiki_url IS NULL` (existing signal, no new status field needed).

## Deferred Ideas

- Manual per-film retry button — Phase 9 (ENRICH-04, ENRICH-05).
- Live progress UI for batch-reload — Phase 11's progress UI is for the bulk-import flow, not this admin job.
- Scheduled/automatic batch-reload — explicitly considered and rejected; this phase only builds an admin-triggered endpoint.
