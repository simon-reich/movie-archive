---
phase: 03-save-movie-flow
fixed_at: 2026-05-17T00:00:00Z
review_path: .planning/phases/03-save-movie-flow/03-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 03: Code Review Fix Report

**Fixed at:** 2026-05-17T00:00:00Z
**Source review:** .planning/phases/03-save-movie-flow/03-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4
- Fixed: 4
- Skipped: 0

## Fixed Issues

### WR-01: @Retryable on WikipediaClient.fetch() never fires — exceptions swallowed inside tryFetch()

**Files modified:** `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java`
**Commit:** ac3c3e3
**Applied fix:** Removed the `@Retryable` annotation (and its `noRetryFor`, `maxAttempts`, `backoff` parameters) from `fetch()`, and removed the now-unused `Backoff` and `Retryable` imports. The annotation had no effect because `tryFetch()` catches all exceptions internally and returns `Optional.empty()`, so `fetch()` never propagated a retryable exception to the Spring AOP proxy.

---

### WR-02: EnrichmentService.enrich() missing @Transactional

**Files modified:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java`
**Commit:** f15f7e1
**Applied fix:** Added `@Transactional` annotation to `enrich()` and the corresponding `import org.springframework.transaction.annotation.Transactional`. The transaction is created inside the async thread (called from `MovieController`, a different bean), which is the correct Spring behaviour for `@Async` + `@Transactional` combination.

---

### WR-03: TmdbClient.search() silently drops results on malformed release_date

**Files modified:** `backend/src/main/java/de/moviearchive/enrichment/TmdbClient.java`
**Commit:** f5ca5a8
**Applied fix:** Replaced the inline ternary `Integer.parseInt(releaseDate.substring(0, 4))` with an explicit `null`-initialised variable and a `try/catch (NumberFormatException ignored)` block. Non-numeric `release_date` prefixes (e.g. `"N/A"`) now produce `year = null` instead of throwing and losing the entire result list.

---

### WR-04: posterUrl() in add.vue does not validate posterPath format

**Files modified:** `frontend/pages/add.vue`
**Commit:** d75d651
**Applied fix:** Added `!posterPath.startsWith('/')` to the early-return guard in `posterUrl()`. Paths that do not begin with `/` (TMDB's documented format) now fall back to `/placeholder-poster.svg` instead of being concatenated directly into the CDN URL.

---

_Fixed: 2026-05-17T00:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
