---
phase: 08-wiki-enrichment-tracking-batch-reload
verified: 2026-08-23T01:25:00Z
status: passed
score: 11/11 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 8: Wiki Enrichment Tracking & Batch Reload Verification Report

**Phase Goal:** The system tracks every Wikipedia enrichment attempt per film and can batch-reload films missing Wikipedia data without re-triggering rate limiting.
**Verified:** 2026-08-23T01:25:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Every Wikipedia enrichment attempt (success or failure) sets `wikiLastAttemptedAt`, in both the save-flow (`EnrichmentService.enrich()`) and the retry path (`WikiReloadService.retryWikipedia()`) | ✓ VERIFIED | `EnrichmentService.java:98` sets `movie.setWikiLastAttemptedAt(Instant.now())` as the first statement inside the try block (covers success + both catch branches); `WikiReloadService.java:61` sets it as the first statement of `retryWikipedia`. Regression-tested: `EnrichmentServiceTest#shouldSaveWithSuccess_whenWikipediaFails`, `#shouldSetWikiLastAttemptedAt_onWikipediaSuccess`, `EnrichmentIntegrationTest#shouldSaveWithSuccess_whenWikipediaFails`, `WikiReloadServiceTest#shouldSetTimestampAndWikiFields_onRetrySuccess`, `#shouldSetTimestampOnly_whenWikipediaNotFound` — all pass (re-run, see below) |
| 2 | `POST /admin/wiki-reload/{userId}` returns 403 on JWT-subject/path-`userId` mismatch (IDOR protection, T-08-01) | ✓ VERIFIED | `WikiReloadController.assertOwnership` throws `AccessDeniedException` → 403 handler. Test `WikiReloadControllerTest#shouldReturn403_whenUserMismatch` passes |
| 3 | Batch-reload endpoint finds only films missing Wikipedia data (`wikiUrl IS NULL`), `status = SUCCESS`, whose last attempt is outside the 30-day cooldown (or never attempted); a film attempted exactly at the cutoff is excluded (`<`, not `<=`) | ✓ VERIFIED | `MovieRepository.findEligibleForWikiReload` JPQL: `wikiUrl IS NULL AND status = SUCCESS AND (wikiLastAttemptedAt IS NULL OR wikiLastAttemptedAt < :cutoff)`. Timing-based integration test `WikiReloadServiceIntegrationTest#shouldRespectCooldownWindow_excludingRecentAttempts` proves a 29-day-old attempt is skipped (unchanged) while never-attempted/31-day-old attempts are retried — passes |
| 4 | Batch-reload paces `Thread.sleep(pacingDelayMs)` between consecutive Wikipedia calls, never after the last item, zero sleeps for 0–1 eligible films | ✓ VERIFIED | `WikiReloadService.batchReload`: `if (i < eligible.size() - 1) { Thread.sleep(pacingDelayMs); }`. Proven by `WikiReloadServiceIntegrationTest#shouldPaceRequestsBetweenEligibleMovies` (3-movie batch ≥ mandatory 2-sleep floor) and `#shouldNotPace_whenOnlyOneMovieEligible` (1-movie batch well under one pacing interval) — both pass |
| 5 | Batch-reload runs fire-and-forget async on a dedicated bounded executor (`core=1/max=1/queue=1`), not on the shared `enrichmentExecutor` | ✓ VERIFIED | `AsyncConfig.wikiReloadExecutor` bean, sized exactly as required; `WikiReloadService.batchReload` annotated `@Async("wikiReloadExecutor")`; controller returns 202 immediately (`ResponseEntity.accepted()`) |
| 6 | A film with `wikiUrl` already set is never re-fetched/overwritten by batch-reload (D-01) | ✓ VERIFIED | Eligibility query filters `wikiUrl IS NULL`; a film with `wikiUrl` set can never appear in `eligible` and is structurally unreachable by `retryWikipedia` via the batch path |
| 7 | TMDB/OMDB raw data and `movie.status` are never modified by `retryWikipedia()`/`batchReload()` (D-01) | ✓ VERIFIED | `grep` of `WikiReloadService.java` confirms no reference to `setRawTmdbJson`, `setRawOmdbJson`, `setStatus`, `tmdbClient`, or `omdbClient` anywhere in the file |
| 8 | On late Wikipedia success, the film is re-indexed into OpenSearch and `indexedAt` is updated (D-02) | ✓ VERIFIED | `retryWikipedia` nested try/catch calls `indexingService.index(movie)` then `movie.setIndexedAt(Instant.now())`. Proven by `WikiReloadControllerTest#shouldRetryWikipediaAndReindex_forMovieMissingWikiData` (asserts `indexedAt` non-null after polling) — passes |
| 9 | A single per-movie failure in the batch loop never aborts processing of remaining eligible movies | ✓ VERIFIED | `batchReload`'s per-iteration try/catch around `retryWikipedia(movie)` logs and continues. `WikiReloadServiceTest#shouldIsolateFailures_inBatchLoop` proves `wikipediaClient.fetch` is invoked exactly twice despite the first call throwing — passes |
| 10 | A second overlapping trigger is queued (not rejected); a third is rejected with 503, not an unhandled 500 (ENRICH-02 concurrency contract) | ✓ VERIFIED | `wikiReloadExecutor` (`queueCapacity=1`) + `WikiReloadController`'s `@ExceptionHandler(TaskRejectedException.class)` → 503. `WikiReloadControllerTest#shouldQueueSecondTrigger_whileFirstRunInProgress` (202) and `#shouldReject_whenThirdTriggerExceedsQueueCapacity` (503, non-empty `message`) both pass |
| 11 | `wiki.retry.cooldown-days` / `wiki.retry.pacing-delay-ms` are configurable via `application.properties` with ENV overrides, defaulting to 30/1000 (D-04/D-08) | ✓ VERIFIED | `application.properties:53-54`: `wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}`, `wiki.retry.pacing-delay-ms=${WIKI_RETRY_PACING_DELAY_MS:1000}` |

**Score:** 11/11 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql` | Additive column migration | ✓ VERIFIED | `ALTER TABLE movies ADD COLUMN wiki_last_attempted_at TIMESTAMPTZ;` — applies cleanly (Testcontainers-backed `ReindexControllerTest` re-ran green) |
| `backend/src/main/java/de/moviearchive/movie/Movie.java` | `wikiLastAttemptedAt` field | ✓ VERIFIED | Line 73: `private Instant wikiLastAttemptedAt;` |
| `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java` | Sets timestamp on every Wikipedia attempt | ✓ VERIFIED | Line 98, first statement in Step 3's try block |
| `backend/src/main/java/de/moviearchive/movie/MovieRepository.java` | `findEligibleForWikiReload` cooldown query | ✓ VERIFIED | Final version present, tracer method (`findByUserIdAndWikiUrlIsNull`) fully replaced per Plan 08-02 |
| `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java` | `retryWikipedia`/`batchReload` | ✓ VERIFIED | Both methods present, correctly annotated |
| `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java` | `POST /admin/wiki-reload/{userId}` | ✓ VERIFIED | Ownership-checked, 202 response, 503 handler |
| `backend/src/main/java/de/moviearchive/config/AsyncConfig.java` | `wikiReloadExecutor` bean | ✓ VERIFIED | `core=1/max=1/queue=1`, distinct from `enrichmentExecutor` |
| `backend/src/main/resources/application.properties` | New config keys | ✓ VERIFIED | Present with correct ENV overrides and defaults |
| Test files (5) | Unit + integration coverage | ✓ VERIFIED | All present, all pass on independent re-run (see Behavioral Spot-Checks) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `WikiReloadController.triggerReload` | `WikiReloadService.batchReload` | direct call | ✓ WIRED | Confirmed in controller source |
| `WikiReloadService.batchReload` | `MovieRepository.findEligibleForWikiReload` | repository call | ✓ WIRED | Confirmed, with `cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS)` |
| `WikiReloadService.batchReload` | `WikiReloadService.retryWikipedia` | loop w/ pacing | ✓ WIRED | Confirmed; note self-invocation caveat below (WR-01) |
| `WikiReloadService.retryWikipedia` | `WikipediaClient.fetch` | direct call | ✓ WIRED | Confirmed |
| `WikiReloadService.retryWikipedia` | `IndexingService.index` | on success | ✓ WIRED | Confirmed, nested try/catch |
| `AsyncConfig.wikiReloadExecutor` | `WikiReloadService.batchReload` | `@Async("wikiReloadExecutor")` | ✓ WIRED | Bean name matches annotation string exactly |
| `WikiReloadController` | `TaskRejectedException` handler | `@ExceptionHandler` | ✓ WIRED | Fires on saturated queue, tested end-to-end |

### Behavioral Spot-Checks

All phase-relevant test classes were re-executed independently by the verifier (not taken from SUMMARY.md claims), using Testcontainers against a real Postgres + OpenSearch + WireMock stack:

| Test Class | Command | Result | Status |
|---|---|---|---|
| `WikiReloadControllerTest` | `./gradlew test --tests ...` | 4/4 tests, 0 failures | ✓ PASS |
| `WikiReloadServiceIntegrationTest` | (same run) | 3/3 tests, 0 failures | ✓ PASS |
| `WikiReloadServiceTest` | (same run) | 3/3 tests, 0 failures | ✓ PASS |
| `EnrichmentServiceTest` | (same run) | 4/4 tests, 0 failures | ✓ PASS |
| `EnrichmentIntegrationTest` | (same run) | 3/3 tests, 0 failures | ✓ PASS |
| `ReindexControllerTest` (regression, proves V8 migration doesn't break Flyway validation) | (same run) | 4/4 tests, 0 failures | ✓ PASS |

Total: 21/21 tests, 0 failures, 0 errors — independently confirmed via fresh `build/test-results/test/TEST-*.xml` output, not SUMMARY.md's reported "132 tests, 0 failures" figure.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| ENRICH-01 | 08-01 | Attempt timestamp on every Wikipedia enrichment attempt | ✓ SATISFIED | Truth #1 |
| ENRICH-02 | 08-01, 08-02 | Batch-reload endpoint, cooldown-filtered eligibility, concurrency contract | ✓ SATISFIED | Truths #2, #3, #10 |
| ENRICH-03 | 08-02 | Paced Wikipedia calls during batch-reload | ✓ SATISFIED | Truth #4 |

No orphaned requirements — REQUIREMENTS.md maps only ENRICH-01/02/03 to Phase 8, and both plans jointly claim all three.

### Anti-Patterns Found

No `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` markers found in any of the 8 production files modified/created by this phase. No stub returns, no hardcoded empty data flowing to callers.

### Code Review Findings (context, non-blocking)

`08-REVIEW.md` (0 critical, 3 warnings, 2 info) flagged two structural issues in this phase's code that do not break the goal's observable truths but are worth carrying forward:

- **WR-01:** `retryWikipedia()`'s `@Transactional` is a no-op when called from `batchReload()` via same-class self-invocation (bypasses the Spring AOP proxy — the identical pitfall CLAUDE.md documents for `@Async`/`@Retryable`, recurring here for `@Transactional`). Currently harmless because the method persists via explicit `save()` calls rather than relying on transactional dirty-checking, but the two `save()` calls in the success branch are not atomic with each other as the JavaDoc implies.
- **WR-02:** `wikiReloadExecutor` is a single **global** bounded queue, not scoped per user. The 503 message ("already in progress for this user") and the queued/rejected tests only demonstrate same-user overlap by coincidence of using one user in the test — no per-user isolation actually exists. Acceptable under this project's declared "single-user-first" v1 scope, but will misbehave the moment a second user exists.
- **WR-03 (pre-existing, not part of this phase's diff):** `EnrichmentService.enrich()` can leave a movie stuck at `PENDING` forever if `settingsService.getApiKeys()` throws (e.g. AES-GCM decrypt failure) before the try/catch that sets `ERROR`. Not introduced by Phase 8; out of scope for this verification.

None of these three findings falsify a must-have truth or a roadmap Success Criterion — they are legitimate hardening opportunities, correctly triaged by the code reviewer as warnings, not blockers. Recommended as follow-up items (e.g. folded into Phase 9's manual-retry work, which reuses `retryWikipedia`) rather than phase-blocking gaps.

### Human Verification Required

None. All must-haves are backend-only, deterministically testable, and were independently re-verified by running the actual test suite (not just reading SUMMARY.md claims).

### Gaps Summary

No gaps. All 3 roadmap Success Criteria and all 11 merged must-have truths from both plans (08-01, 08-02) are verified against the actual codebase: migration applies, entity field exists, both Wikipedia-attempt paths set the timestamp, the batch-reload endpoint is ownership-checked and functional end-to-end, cooldown-window filtering and pacing both have real timing-based integration test proof (not just mocked assumptions), concurrency degrades gracefully (queue then 503), and D-01/D-02 scope constraints (Wikipedia-only retry, re-index on late success) are honored in code. Independent test execution confirms 21/21 relevant tests pass with 0 failures/errors.

---

_Verified: 2026-08-23T01:25:00Z_
_Verifier: Claude (gsd-verifier)_
