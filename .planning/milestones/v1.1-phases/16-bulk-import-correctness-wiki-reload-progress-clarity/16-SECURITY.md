---
phase: 16
slug: bulk-import-correctness-wiki-reload-progress-clarity
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: 2026-08-29
---

# Phase 16 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Authenticated user -> BulkImportService batch-scoped repository queries | `batchId` always originates server-side (upload-time `createBatch()` or already ownership-checked `resolveLine()`/`loadOwnedBatch()`) | none new — existing batch-scoped ownership check |
| TMDB search results -> automatic save decision | Untrusted third-party API response data directly determines which `Movie` gets auto-saved without human review (multi-stage matching, D-10/D-11) | TMDB match candidates |
| SSE progress stream (existing, unchanged) -> settings.vue | Registry keyed per already-authenticated `userId`; this phase adds fields to the broadcast payload but changes neither subscriber nor trigger auth | wiki-reload progress fields (`stopped`, per-movie history) |
| `wikiMovieHistory` per-movie history list | Client-side array push driven by the already-authenticated per-userId SSE stream | per-movie processed status |
| `MovieRepository.findEligibleForWikiReload` -> `WikiReloadService.batchReload()`'s paced Wikipedia calls | Existing, already-ownership-scoped query; only the WHERE predicate changed | paced external HTTP calls to Wikipedia |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-16-01 | Tampering | New batch-scoped `BulkImportLineRepository` query methods | high | mitigate | Every new method requires both `userId` and `batchId` (never `batchId` alone), mirroring the existing `findByIdAndBatchId`/`loadOwnedBatch()` defense-in-depth convention — verified by `shouldNotReuseRow_acrossDifferentBatchIds` and a real-DB integration test (16-01-SUMMARY.md) | closed |
| T-16-02 | Repudiation / Integrity | `processLine()`'s multi-stage TMDB auto-match (D-10-D-12) | medium | mitigate | The unchanged D-04 "never auto-guess when more than one candidate survives narrowing" invariant is preserved — any non-collapsing candidate set stays `AMBIGUOUS` and requires the existing manual-resolve endpoint | closed |
| T-16-03 | Tampering | `existingSaved` batch-scoped fast-path (D-02/D-03) | low | accept | Cross-batch SAVED short-circuit removed; `movieService.initiate()`'s tmdbId-idempotency (unchanged) prevents a duplicate `Movie` row — only cost is one redundant TMDB call, explicitly accepted per D-03 | closed |
| T-16-04 | Information Disclosure | `ProgressState.stopped` (new SSE payload field) | low | accept | `stopped` is a boolean derived server-side from `isStopRequested(userId)`, scoped to the same already-authenticated `userId` as every other payload field — no new information crosses a trust boundary | closed |
| T-16-05 | Tampering | `complete()`'s `stopped`-read-before-`stopFlags.remove()` ordering (D-04) | medium | mitigate | Explicit ordering requirement enforced (read `isStopRequested` before `stopFlags.remove`), directly asserted by `requestStop_thenComplete_reportsStoppedTrueAndRealProcessedCount` | closed |
| T-16-06-01 | Repudiation (data integrity of user-visible record) | `wikiMovieHistory` per-movie history list | low | mitigate | The `!p.complete` guard (16-03) restores a 1:1 mapping between real per-movie `progress` events and history rows — verified by two-event-sequence regression tests, and confirmed live during UAT re-verification (2026-08-29) | closed |
| T-16-07-01 | Denial of Service (of a shared external resource) | `MovieRepository.findEligibleForWikiReload` -> `WikiReloadService.batchReload()`'s paced Wikipedia calls | medium | mitigate | Keying eligibility on `wiki_url IS NULL` (16-04) permanently excludes an already-found page from future runs, eliminating the prior unbounded re-fetch of 41+ movies on every batch-reload run — verified by `WikiReloadServiceIntegrationTest` | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on (high) count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| R-16-01 | T-16-03 | Removing the cross-batch SAVED fast-path costs one redundant TMDB call on a title/year collision across batches; tmdbId-idempotency in `movieService.initiate()` prevents any duplicate row. Simplicity over saving one redundant search (D-03). | Plan 16-01 (planner) | 2026-08-24 |
| R-16-02 | T-16-04 | `ProgressState.stopped` is a per-authenticated-user boolean derived server-side; adds no new trust-boundary crossing beyond the existing SSE payload fields. | Plan 16-02 (planner) | 2026-08-28 |

*If none: "No accepted risks."*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-08-29 | 7 | 7 | 0 | gsd-secure-phase (orchestrator, L1 grep-depth — register authored at plan time, asvs_level 1, short-circuit per workflow step 3) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-08-29
