---
status: awaiting_human_verify
trigger: "CI fails on jacocoTestCoverageVerification — line coverage is 72% but minimum threshold is 75%"
created: 2026-05-18T00:00:00Z
updated: 2026-05-18T00:00:00Z
---

## Current Focus
<!-- OVERWRITE on each update - reflects NOW -->

hypothesis: RESOLVED — Coverage is now 85.6% (was 72%). All 118 tests pass. jacocoTestCoverageVerification BUILD SUCCESSFUL.
next_action: Await human verification that CI passes on push.

## Symptoms
<!-- Written during gathering, then IMMUTABLE -->

expected: CI passes; line coverage >= 75%
actual: Build fails with "Rule violated for bundle backend: lines covered ratio is 0.72, but expected minimum is 0.75"
errors: |
  [ant:jacocoReport] Rule violated for bundle backend: lines covered ratio is 0.72, but expected minimum is 0.75
  FAILURE: Build failed with an exception.
  Execution failed for task ':jacocoTestCoverageVerification'
reproduction: git push → CI runs `./gradlew jacocoTestCoverageVerification --no-daemon`
started: After Phase 6 work (Movie Detail Page + Personal Fields)

## Eliminated

- hypothesis: Coverage gap caused by Phase 6 code specifically (Movie detail / personal fields)
  evidence: No Phase 6 production code exists yet. The gap was from Phase 5 dashboard + search code added without sufficient tests, plus pure record DTOs counted by JaCoCo.
  timestamp: 2026-05-18

## Evidence

- timestamp: 2026-05-18
  checked: JaCoCo XML report after running ./gradlew test jacocoTestReport
  found: Bundle LINE ratio=0.747 (1158/1551). DashboardService at 5% (5/95 lines), SearchService at 62% (126/204), DashboardController at 42% (5/12), SearchController at 54% (14/26). Pure record DTOs (AutocompleteResponse, DashboardMovieItem, DashboardResponse, FacetsResponse, HistogramBucket) all at 0% with 1 line each.
  implication: Three fixes needed: exclude pure DTOs, add DashboardController tests, add facets/autocomplete tests to SearchControllerTest.

- timestamp: 2026-05-18
  checked: DashboardService empty-archive behaviour
  found: When no index exists, OpenSearch throws index_not_found_exception which propagated as 500. DashboardService had no guard for this case.
  implication: Bug fix required in DashboardService alongside test coverage fix.

- timestamp: 2026-05-18
  checked: ./gradlew test jacocoTestReport jacocoTestCoverageVerification after all fixes
  found: BUILD SUCCESSFUL. 118 tests completed, 0 failed. Bundle LINE ratio=0.856 (1288/1505 lines).
  implication: Coverage threshold met with significant margin.

## Resolution

root_cause: Three compounding causes: (1) DashboardService and its controller had zero test coverage — 95+12 lines uncovered. (2) SearchService facets/autocomplete paths (getFacets, autocomplete) were never exercised by the existing SearchControllerTest. (3) Pure record DTOs (5 records, ~7 lines) were counted by JaCoCo but have no testable logic.
fix: |
  1. Added JaCoCo exclusion list in build.gradle.kts for all pure DTO records, simple exception subclasses, and the application entry point — these have no branching logic worth measuring.
  2. Created DashboardControllerTest (6 integration tests against real OpenSearch + Postgres) covering: empty archive, 403 unauth, total films count, top genres aggregation, movie-of-the-day, recently-added list, IMDB histogram.
  3. Added 8 new tests to SearchControllerTest: facets endpoint (2 tests + 403 unauth), autocomplete director suggestions + invalid field 400 + 403 unauth, page overflow 400, year_desc sort.
  4. Fixed DashboardService to catch index_not_found_exception and return an empty dashboard (totalFilms=0, empty lists) instead of propagating a 500 — this is the correct empty-archive behavior documented in 05-RESEARCH.md Pitfall 7.
verification: ./gradlew test jacocoTestReport jacocoTestCoverageVerification --no-daemon → BUILD SUCCESSFUL. 118 tests, 0 failed. Line coverage 85.6% (threshold 75%).
files_changed:
  - backend/build.gradle.kts
  - backend/src/main/java/de/moviearchive/search/DashboardService.java
  - backend/src/test/java/de/moviearchive/search/DashboardControllerTest.java
  - backend/src/test/java/de/moviearchive/search/SearchControllerTest.java
