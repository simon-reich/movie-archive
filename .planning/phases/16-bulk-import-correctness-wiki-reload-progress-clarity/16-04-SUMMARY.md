---
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
plan: 04
subsystem: enrichment
tags: [spring-data-jpa, wiki-reload, testcontainers, jpql]

# Dependency graph
requires:
  - phase: 08-wiki-enrichment-tracking-batch-reload
    provides: MovieRepository.findEligibleForWikiReload, WikiReloadService batch-reload pipeline
provides:
  - Wiki-reload retry-eligibility keyed on wiki_url IS NULL, reconciled with WikiRetryOutcome's existence-based "found" semantics
affects: [16-notfound-icon-shows-checkmark debug doc, any future WikipediaClient section-name-allowlist follow-up]

# Actuals (#2632)
actuals:
  tokens: 2057
  tasks: 1
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns: [Superseded-decision javadoc pattern: document the old rationale being reversed inline in the query's javadoc, not deleted, so future readers understand why the definition changed]

key-files:
  created: []
  modified:
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java

key-decisions:
  - "Keyed findEligibleForWikiReload's WHERE clause on wiki_url IS NULL instead of wiki_plot IS NULL AND wiki_critics IS NULL, aligning retry-eligibility with WikiRetryOutcome.SUCCESS's existing 'a page was located' semantics — the simplest correct fix per the product decision recorded in 16-CONTEXT.md, with WikipediaClient's section-name allowlist limitation explicitly left out of scope"

patterns-established: []

requirements-completed: [D-09]

coverage:
  - id: D1
    description: "A movie whose Wikipedia page has already been resolved (wiki_url set) is never re-selected by wiki-reload batch-reload again, even if wiki_plot/wiki_critics were never extracted from that page (G-16-3)"
    requirement: "D-09"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java#shouldNotRetryMovies_withUrlAlreadySet_regardlessOfContent"
        status: pass
    human_judgment: false
  - id: D2
    description: "All pre-existing repository/service tests still pass with the new eligibility definition (no regression to cooldown-window, pacing, or SPARQL-batching behavior)"
    verification:
      - kind: integration
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java (5/5), EnrichmentIntegrationTest.java (5/5)"
        status: pass
      - kind: unit
        ref: "backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java (10/10)"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-08-29
status: complete
---

# Phase 16 Plan 04: Wiki-Reload Eligibility Reconciled with SUCCESS Checkmark Summary

**Keyed `MovieRepository.findEligibleForWikiReload` on `wiki_url IS NULL` instead of `wiki_plot`/`wiki_critics`, so a movie whose page was already found is never re-selected for retry again, closing gap G-16-3.**

## Performance

- **Duration:** 12 min
- **Started:** 2026-08-29T17:44:00Z
- **Completed:** 2026-08-29T17:56:00Z
- **Tasks:** 1
- **Files modified:** 2

## Accomplishments
- `findEligibleForWikiReload`'s `@Query` WHERE clause changed from `wiki_plot IS NULL AND wiki_critics IS NULL` to `wiki_url IS NULL`, permanently excluding an already-resolved Wikipedia page from future batch-reload runs regardless of extracted content
- Javadoc rewritten to document this as a superseding decision over the prior content-based rationale, preserving the historical context (why the old definition existed, why it's now reversed, and the accepted trade-off)
- Replaced the now-inverted `shouldRetryMovies_withUrlSetButNoContent` regression test with `shouldNotRetryMovies_withUrlAlreadySet_regardlessOfContent`, asserting both the repository-level exclusion (deterministic) and that `batchReload()` never even attempts a re-fetch (via a 500ms confirm-nothing-happened wait, matching the existing pattern elsewhere in the codebase)
- Full RED/GREEN TDD cycle run and verified: new test fails against the old query, passes against the fix

## Task Commits

Each task was committed atomically (TDD RED/GREEN):

1. **Task 1 (RED):** `test(16-04): add failing regression test for wiki-reload URL-already-set exclusion (G-16-3)` - `303e322`
2. **Task 1 (GREEN):** `feat(16-04): key wiki-reload eligibility off wiki_url IS NULL (G-16-3)` - `905d6f8`

_No REFACTOR commit — the fix required no cleanup pass beyond the javadoc rewrite included in the GREEN commit._

## Files Created/Modified
- `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` - `findEligibleForWikiReload`'s `@Query` WHERE clause and javadoc
- `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` - Regression test superseded to assert the new, correct exclusion

## Decisions Made
- Followed the plan's pre-made product decision exactly: key eligibility on `wiki_url IS NULL` (simplest correct fix, no new schema/state) rather than expanding `WikipediaClient`'s section-name allowlist (a separate, larger-scope fix left explicitly out of scope for this plan)
- Kept the superseded rationale in the javadoc rather than deleting it, per the plan's explicit instruction, so a future reader understands why the definition changed and what trade-off was accepted

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

**Environment quirk (not a code issue):** Testcontainers' `UnixSocketClientProviderStrategy` (cached in this machine's `~/.testcontainers.properties`) targets the default `/var/run/docker.sock`, which doesn't exist under this machine's OrbStack Docker setup (socket lives at `~/.orbstack/run/docker.sock`). This is the same pre-existing local-machine quirk documented in every prior phase's SUMMARY back to Phase 08. Worked around per-invocation by setting `DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock` for each `./gradlew test` run in this session — no repo files or global config changed.

## Known Stubs

None.

## Threat Flags

None — this plan's threat model (T-16-07-01, DoS-of-shared-external-resource mitigation) is already fully addressed by the query change itself; no new surface introduced.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- G-16-3 fully closed: a movie with `wiki_url` set will never resurface as eligible for wiki-reload again, and the per-movie history's SUCCESS checkmark now means what it says.
- `WikipediaClient`'s fixed section-name allowlist (why some genuinely-found pages never populate `wiki_plot`/`wiki_critics`) remains a known, explicitly out-of-scope limitation — noted in the updated javadoc as a candidate follow-up, not blocking this milestone's close.
- All 20 tests in the targeted verification suite (`WikiReloadServiceIntegrationTest` 5/5, `EnrichmentIntegrationTest` 5/5, `WikiReloadServiceTest` 10/10) pass.

---
*Phase: 16-bulk-import-correctness-wiki-reload-progress-clarity*
*Completed: 2026-08-29*
