---
phase: 15
slug: bulk-import-page-completion-view-toggle-movie-links-real-csv
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: 2026-08-28
---

# Phase 15 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Authenticated user (JWT) → `BulkImportController.getBatchDetail()` | Existing ownership-checked boundary (`loadOwnedBatch()`), response payload extended with `movieId`/`rawLine` (15-01) | Own batch's line data (movieId, raw uploaded text) |
| Server-rendered `rawLine` text → browser DOM | User's own previously-uploaded file content, rendered back to that same user (15-01, relocated by 15-04) | Raw uploaded line text, Vue-auto-escaped |
| Authenticated user (JWT) → `POST .../lines/{lineId}/resolve` | New endpoint, new path variable (`lineId`) — primary new attack surface of this phase (15-02) | tmdbId, posterPath (client-supplied) |
| Request body (tmdbId, posterPath) → persisted `BulkImportLine` row | Client-supplied data written to DB, later rendered back to the same user (15-02) | tmdbId (validated), posterPath (untrusted string, used only as `:src`) |
| Uploaded file content → `ImportLineParser.parseCsv()` | Untrusted user-supplied bytes parsed by a new third-party library, Apache Commons CSV, for the first time in this codebase (15-03) | Raw CSV bytes |
| `movieId`-derived `:to` href → `NuxtLink` real navigation | Previously inert due to a Nuxt component-resolution bug; now actually navigates (15-04) — value itself unchanged, only rendering mechanism fixed | Ownership-checked movieId (unchanged source) |
| `candidate.title`/`candidate.year` (TMDB search response) → visible `{{ }}`-interpolated text node | Same TMDB data already trusted and rendered elsewhere on this page as `alt`/`{{ line.title }}` (15-05, unchanged mechanism in 15-06) | TMDB title/year strings |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-15-01a | Elevation of Privilege | `BulkImportController.getBatchDetail()` movieId lookup | low | accept | `movieId` resolved via `batch.getUser().getId()`; `batch` already ownership-verified by pre-existing `loadOwnedBatch()`. Verified by `shouldReturn403_whenDifferentUserRequestsBatchDetail`. | closed |
| T-15-02 | Information Disclosure | `frontend/pages/imports/[batchId].vue` rawLine rendering | low | accept | Own content, own-owner-only visibility; Vue `{{ }}` auto-escapes, `v-html` never used. | closed |
| T-15-01 (resolve endpoint) | Elevation of Privilege | `POST /movies/bulk-import/batches/{batchId}/lines/{lineId}/resolve` | high | mitigate | `loadOwnedBatch()` verifies batchId ownership; `BulkImportLineRepository.findByIdAndBatchId(lineId, batchId)` additionally verifies lineId belongs to that specific batch — a lineId from another batch/user resolves to 404/403. Tested via `shouldReturn404_whenResolvingLineFromDifferentBatch` and `shouldReturn403_whenDifferentUserResolvesLine`. | closed |
| T-15-03 | Tampering | `ResolveLineRequest.tmdbId` | medium | mitigate | `@Positive` validation rejects zero/negative tmdbId at the controller boundary (mirrors `SaveMovieRequest`). | closed |
| T-15-04 | Tampering | `ResolveLineRequest.posterPath` (client-supplied) | low | accept | Only tampers with the requesting user's own display data (gated by T-15-01 ownership check); consumed only via `:src`, never `v-html`. Mirrors existing `BulkImportService` pattern. | closed |
| T-15-05 | Denial of Service | `ImportLineParser.parseCsv()` malformed record field access | medium | mitigate | `record.size() != 3` checked before any `.get(index)` call — degrades to `PARSE_ERROR` instead of an uncaught exception. Tested (Task 2 acceptance criteria). | closed |
| T-15-06 | Denial of Service | Adversarially long unquoted CSV line (unbounded memory) | low | accept | Pre-existing, out-of-scope gap not introduced by this phase — `bulk-import.max-lines` bounds line count for both formats equally, unchanged from before this phase. | closed |
| T-15-07 | Tampering | CSV formula/injection if data is later exported to Excel | low | accept | Out of scope — no CSV export exists in this phase (deferred to backlog item SET-05); noted for whoever builds export later. | closed |
| T-15-08 | Tampering | `org.apache.commons:commons-csv:1.14.1` supply chain | low | accept | Manually vetted in `15-RESEARCH.md`'s Package Legitimacy Audit: ASF top-level project, ~13-year history — Approved, no blocking flag. | closed |
| T-15-04-01 | Information Disclosure | PARSE_ERROR row's `raw-line-text` (relocated to always-row section) | low | accept | Identical content/escaping/visibility as T-15-02 — only DOM location and CSS shape changed. | closed |
| T-15-04-02 | Tampering | `:is="movieLinkTarget(line) ? NuxtLink : 'div'"` navigation target | low | accept | href still derived exclusively from the ownership-checked movieId established in 15-01 (T-15-01a) — this plan only fixed the rendering mechanism, not the value's source. | closed |
| T-15-05-01 | Information Disclosure / Tampering (XSS) | `resolve-candidate-label` rendering `candidate.title` | low | accept | Rendered exclusively via Vue's auto-escaping `{{ }}` interpolation, identical to existing title-render patterns on this page. | closed |
| T-15-06-01 | Information Disclosure / Tampering (XSS) | `resolve-candidate-label` rendering via `candidateLabel()` (CSS-only change) | low | accept | No change to rendering mechanism — still `{{ }}` auto-escaping from 15-05; only a Tailwind class was removed. | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on (high) count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-15-01 | T-15-01a | Movie lookup scoped through already ownership-verified batch; no new boundary. | Plan-time (15-01) | 2026-08-28 |
| AR-15-02 | T-15-02 | Own content rendered only to own user, auto-escaped. | Plan-time (15-01) | 2026-08-28 |
| AR-15-03 | T-15-04 | posterPath tampering scoped to attacker's own account data, no v-html. | Plan-time (15-02) | 2026-08-28 |
| AR-15-04 | T-15-06 | Long-line DoS is a pre-existing, unchanged gap covered by existing max-lines bound. | Plan-time (15-03) | 2026-08-28 |
| AR-15-05 | T-15-07 | CSV export (where formula-injection would matter) does not exist yet; deferred to SET-05. | Plan-time (15-03) | 2026-08-28 |
| AR-15-06 | T-15-08 | commons-csv vetted via Package Legitimacy Audit — ASF project, long history, no red flags. | Plan-time (15-03) | 2026-08-28 |
| AR-15-07 | T-15-04-01 | Content relocation only, identical trust boundary as T-15-02. | Plan-time (15-04) | 2026-08-28 |
| AR-15-08 | T-15-04-02 | Link target value unchanged, only fixed a broken rendering mechanism. | Plan-time (15-04) | 2026-08-28 |
| AR-15-09 | T-15-05-01 | Identical auto-escaping pattern already used elsewhere on this page. | Plan-time (15-05) | 2026-08-28 |
| AR-15-10 | T-15-06-01 | CSS-only change, no new render mechanism. | Plan-time (15-06) | 2026-08-28 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-08-28 | 13 | 13 | 0 | Claude (gsd-secure-phase, State B — built from PLAN.md threat models, ASVS L1 short-circuit, no auditor spawn required) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-08-28
