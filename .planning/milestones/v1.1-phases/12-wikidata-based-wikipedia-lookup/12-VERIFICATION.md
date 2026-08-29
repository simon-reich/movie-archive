---
phase: 12-wikidata-based-wikipedia-lookup
verified: 2026-08-26T17:45:00Z
status: passed
score: 6/6 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 12: Wikidata-based Wikipedia lookup Verification Report

**Phase Goal:** Wikipedia lookup uses the Wikidata IMDb-ID cross-reference (property P345) first for a direct, unambiguous article resolution, instead of guessing up to 10 URL candidates; falls back to the existing candidate search when no Wikidata link exists.
**Verified:** 2026-08-26T17:45:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `WikipediaClient.fetch()` tries a Wikidata P345 lookup first using `movie.imdbId`, before the existing candidate-URL cascade (D-01) | ✓ VERIFIED | `WikipediaClient.java:157-163` — `fetch()` calls `tryFetchViaWikidata(imdbId)` as the very first statement, before `buildCandidates(...)` is even invoked. Confirmed behaviorally: `WikipediaClientTest#shouldReturnResult_viaWikidata_whenImdbIdMatchesP345` asserts exactly 1 search-API request + 1 sitelinks-API request and zero candidate-cascade requests — test passes (green, see Behavioral Spot-Checks). |
| 2 | When imdbId is missing, no P345 match, or no enwiki sitelink, `fetch()` falls through unchanged into the existing cascade (D-01) | ✓ VERIFIED | `tryFetchViaWikidata()` (`WikipediaClient.java:219-259`) returns `Optional.empty()` on: null/blank imdbId (early return, no HTTP call), empty search hits array, null/blank qid, null sitelink response, null/blank resolved title, any `WebClientResponseException` (429 handled via `recordRateLimited`, all others incl. 404 via `log.debug`), and any generic `Exception`. Every path lands on `Optional.empty()`, never throwing `WikipediaNotFoundException` directly. Behaviorally proven by 3 passing tests: `shouldFallThroughToCascade_whenWikidataSearchHasNoHits`, `shouldFallThroughToCascade_whenWikidataItemHasNoEnwikiSitelink`, `shouldHonorRetryAfterBackoff_onWikidataCall` (all assert the cascade is reached and ultimately throws `WikipediaNotFoundException`, or that the shared backoff engages). |
| 3 | OMDB's client, call order, and field mappings are untouched (D-02) | ✓ VERIFIED | `git log` shows `OmdbClient.java` was last modified in phase 03-04 (commit `86b57c5`), with zero commits touching it since. `git show --stat` on all 3 phase-12 commits (`477cf9a`, `7cd031a`, `7da91b5`) confirms `OmdbClient.java` does not appear in any changed-file list. `EnrichmentService.java` Step 2 (OMDB, lines 81-95) is byte-identical aside from being untouched by the diff (`git diff` confirms only line 103, the Wikipedia call site, changed). |
| 4 | `EnrichmentService.enrich()`, `WikiReloadService.retryWikipedia()`, and (transitively) `batchReload()` all pass `movie.getImdbId()` through with zero caller-side special-casing (D-03) | ✓ VERIFIED | `EnrichmentService.java:103` and `WikiReloadService.java:79` both changed from a 3-arg to a 4-arg `fetch(origTitle, movieTitle, year, movie.getImdbId())` call — a single added argument, no branching/special-casing added. `batchReload()` (`WikiReloadService.java:113-138`) calls `self.retryWikipedia(movie)` unchanged, so it transitively exercises the new path. `grep -rn "wikipediaClient.fetch"` across `backend/src/main` confirms these are the ONLY two production call sites of `WikipediaClient.fetch()` — no missed caller. Full integration proof: `WikiReloadServiceIntegrationTest` (4 tests, real Spring context + Postgres + OpenSearch + WireMock, exercising the real `WikipediaClient` bean through `batchReload()`→`retryWikipedia()`→`fetch()`) — ran in isolation, BUILD SUCCESSFUL. |
| 5 | No active/forced backfill or bulk-reprocessing trigger is added anywhere (D-04) | ✓ VERIFIED | All 3 phase-12 commits (`477cf9a`, `7cd031a`, `7da91b5`) touch only: `WikipediaClient.java`, `EnrichmentService.java` (1-line call-site change), `WikiReloadService.java` (1-line call-site change), `application.properties`, `.gitignore`, and test/fixture files. No new `@RestController`, `@Scheduled`, or endpoint was added; no existing endpoint gained new bulk/backfill logic. The ~630 previously-failed films are untouched by this phase's diff. |
| 6 | A temporary, human-readable, plain-text resolution log is written at 3 outcome points in `fetch()`, distinct from SLF4J, and `backend/wiki-resolution.log` is gitignored (D-05) | ✓ VERIFIED | `logResolution()` (`WikipediaClient.java:195-204`) uses `Files.writeString(..., CREATE, APPEND)` — a separate file write, not `log.debug/info/warn`. Called at exactly 3 outcome points inside `fetch()`: Wikidata hit (line 161), candidate-cascade hit (line 170), search-fallback hit (line 179) — plus the not-found path (line 183) before the final throw, matching the plan's documented 3-outcome-point + not-found design. Directly observed live during test execution: `cat backend/wiki-resolution.log` produced exactly the documented format — `Inception (2010): found via Wikidata`, `Inception (2010): fallback candidate #1 (Inception_(2010_film))`, `Inception (2010): not found` — plain text, not JSON, not SLF4J-formatted. `git check-ignore -v backend/wiki-resolution.log` confirms `.gitignore:38` covers it; `git status --porcelain` shows it untracked. |

**Score:** 6/6 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` | `tryFetchViaWikidata()`, second `wikidataWebClient`, `logResolution()`, 4-arg `fetch()` | ✓ VERIFIED | All present, read in full; wired as described above. |
| `backend/src/test/resources/fixtures/wikidata/search-found.json` | Wikidata search hit fixture (`"title":"Q25188"`) | ✓ VERIFIED | Present, matches live-verified shape from RESEARCH.md; used by 2 passing tests. |
| `backend/src/test/resources/fixtures/wikidata/search-not-found.json` | Empty-hit fixture (`totalhits:0`) | ✓ VERIFIED | Present; used by `shouldFallThroughToCascade_whenWikidataSearchHasNoHits` (passing). |
| `backend/src/test/resources/fixtures/wikidata/sitelinks-found.json` | REST sitelinks response (`{title, url, badges}`) | ✓ VERIFIED | Present; used by 2 passing tests. |
| `backend/wiki-resolution.log` (runtime-generated, gitignored) | Temporary per-attempt log | ✓ VERIFIED | Generated during test runs with correct format; gitignored; deleted after verification (not committed). |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `EnrichmentService.enrich()` | `WikipediaClient.fetch(imdbId)` | direct call, `movie.getImdbId()` as 4th arg | ✓ WIRED | `EnrichmentService.java:103` |
| `WikiReloadService.retryWikipedia()` | `WikipediaClient.fetch(imdbId)` | direct call, `movie.getImdbId()` as 4th arg | ✓ WIRED | `WikiReloadService.java:79` |
| `tryFetchViaWikidata()` | existing `tryFetch(title)` | delegation on resolved title | ✓ WIRED | `WikipediaClient.java:247` — `return tryFetch(resolvedTitle.replace(' ', '_'));` |
| `tryFetchViaWikidata()` 429 handling | shared `backoffUntil` / `recordRateLimited()` | same `AtomicReference` used for en.wikipedia.org 429s | ✓ WIRED | `WikipediaClient.java:249-250` calls the same `recordRateLimited()` method already used by `tryFetch()`/`tryFetchViaSearch()`; behaviorally proven by `shouldHonorRetryAfterBackoff_onWikidataCall` (elapsed ≥ 950ms, passing). |

### Behavioral Spot-Checks

Ran the plan's own targeted test commands directly (not trusting SUMMARY.md's reported results):

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full targeted suite (7+3+4 tests) | `cd backend && DOCKER_HOST=... ./gradlew test --tests WikipediaClientTest --tests WikiReloadServiceTest --tests EnrichmentServiceTest` | BUILD SUCCESSFUL | ✓ PASS |
| Per-class result breakdown | inspected `build/test-results/test/TEST-*.xml` | `WikipediaClientTest`: tests=7 failures=0 errors=0; `WikiReloadServiceTest`: tests=3 failures=0; `EnrichmentServiceTest`: tests=4 failures=0 | ✓ PASS |
| Real-Spring integration path (D-03) | `./gradlew test --tests WikiReloadServiceIntegrationTest` | BUILD SUCCESSFUL (4 tests: cooldown window, retry-with-no-content, pacing, single-movie-no-pacing) | ✓ PASS |
| Environment-flake sanity check | `./gradlew test --tests WikipediaClientTest --tests SettingsIntegrationTest` (mirrors orchestrator's isolation claim) | BUILD SUCCESSFUL | ✓ PASS — confirms the reported full-suite flake is unrelated to this phase's diff |
| D-05 log format, observed live | `rm -f wiki-resolution.log && ./gradlew test --tests WikipediaClientTest ... && cat wiki-resolution.log` | 7 lines, format `Title (Year): outcome`, includes `found via Wikidata`, `fallback candidate #1 (...)`, `not found` | ✓ PASS |
| D-02 OMDB untouched | `git log`/`git show --stat` on `OmdbClient.java` and all 3 phase-12 commits | Last touched in phase 03-04; zero phase-12 commits reference it | ✓ PASS |
| D-04 no backfill added | `git show --stat` on all 3 phase-12 commits | Only enrichment package + properties + gitignore + tests/fixtures changed; no new controller/scheduled job | ✓ PASS |
| Parameterized URI usage (not string concat) for Wikidata calls | Manual code read, `WikipediaClient.java:225-227, 238-239` | Both Wikidata calls use `.uri(template, imdbId)` / `.uri(template, qid)` parameterized form | ✓ PASS |

### Requirements Coverage

No formal REQUIREMENTS.md IDs are assigned to Phase 12 (confirmed: `grep` for "Phase 12"/D-01..D-05 in REQUIREMENTS.md returns nothing) — per ROADMAP.md line 137, "decisions from 12-CONTEXT.md serve as the requirement contract." All 5 decisions (D-01 through D-05) are covered by the Observable Truths table above. No orphaned requirements.

### Anti-Patterns Found

Scanned all files modified by this phase (`WikipediaClient.java`, `EnrichmentService.java`, `WikiReloadService.java`, `application.properties`, `.gitignore`) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` and stub patterns — none found. No debt markers.

### Human Verification Required

None. All 6 must-haves resolved to VERIFIED via direct code reading, git history, and independently re-run automated tests (not just trusting SUMMARY.md's reported pass counts).

### Gaps Summary

None. The one documented deviation in SUMMARY.md (fixing `anyString()` → `nullable(String.class)` in `WikiReloadServiceTest.java`/`EnrichmentServiceTest.java` because Mockito's `anyString()` does not match null) was independently confirmed present in both files and is a legitimate, in-scope bugfix confined to the two test files the plan already listed as modified — it does not affect production behavior or scope.

---

_Verified: 2026-08-26T17:45:00Z_
_Verifier: Claude (gsd-verifier)_
