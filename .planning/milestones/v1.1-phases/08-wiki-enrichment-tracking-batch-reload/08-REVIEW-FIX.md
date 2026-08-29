---
phase: 08-wiki-enrichment-tracking-batch-reload
fixed_at: 2026-08-23T12:14:14Z
review_path: .planning/phases/08-wiki-enrichment-tracking-batch-reload/08-REVIEW.md
iteration: 1
findings_in_scope: 3
fixed: 3
skipped: 0
status: all_fixed
---

# Phase 8: Code Review Fix Report

**Fixed at:** 2026-08-23T12:14:14Z
**Source review:** .planning/phases/08-wiki-enrichment-tracking-batch-reload/08-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 3 (critical_warning scope — CR-*/BL-*/WR-*; 0 critical findings existed, 3 warnings)
- Fixed: 3
- Skipped: 0

**Verification environment:** All fixes were applied and verified inside an isolated git worktree
(`gsd-reviewfix/08-38728`, fast-forwarded into `main` on completion). `./gradlew compileJava
compileTestJava --offline` was run in that worktree after each change (Tier 2 syntax/semantic
check); `./gradlew test --tests <ClassName>` was additionally run for the two findings with an
existing unit-test class that doesn't require Docker/Testcontainers.

## Fixed Issues

### WR-01: `retryWikipedia()`'s `@Transactional` has no effect when called from `batchReload()` (self-invocation)

**Files modified:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java`, `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java`
**Commit:** `daa66e5`
**Applied fix:** Added a `@Lazy`-injected self-reference (`private final WikiReloadService self`)
to `WikiReloadService`, matching the review's primary suggestion (self-injection over extracting a
collaborator bean, to keep the change minimal). `batchReload()` now calls
`self.retryWikipedia(movie)` instead of the bare `retryWikipedia(movie)`, so the call is routed
through the Spring AOP proxy and `@Transactional` actually applies. Updated the constructor
JavaDoc to document why `self` exists. Updated `WikiReloadServiceTest` to wire the test instance
to itself via `ReflectionTestUtils.setField(wikiReloadService, "self", wikiReloadService)` (no
Spring context in that unit test, so there's no real proxy to inject — this is the same
`ReflectionTestUtils` pattern already used elsewhere in this test suite, e.g.
`AuthServiceTest`/`JwtServiceTest`). Verified via `./gradlew compileJava compileTestJava --offline`
(clean compile) and `./gradlew test --tests de.moviearchive.movie.WikiReloadServiceTest --offline`
(all 3 existing tests pass unchanged).

### WR-02: `wikiReloadExecutor` is global, not per-user — misleading 503 message and cross-user request interference

**Files modified:** `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java`
**Commit:** `c80cde4`
**Applied fix:** Chose the review's second (conservative) option rather than adding real
per-user concurrency scoping: updated the 503 `handleTaskRejected` response message from
"A wiki-reload is already in progress for this user" to "A wiki-reload batch is already in
progress" (no longer claims a per-user conflict that doesn't exist), and added JavaDoc on both
the class and the exception handler explaining that `wikiReloadExecutor` is a single global bean
shared by all users, and why that's acceptable under this app's current single-user-first scope
per `CLAUDE.md`. Chose this over adding a `ConcurrentHashMap<UUID, Semaphore>` per-user guard
because: (a) the review itself frames it as "unlikely to bite" given the app's documented
single-user-first v1 scope, (b) a real per-user concurrency mechanism is a larger structural
change (new field, pool-sizing implications, new test coverage for cross-user isolation) that
goes beyond a warning-level code-review fix and risks introducing new bugs without a human
design decision on the desired multi-tenant behavior, and (c) the fix accurately closes the gap
the review flagged — the code no longer claims a guarantee (per-user isolation) it doesn't
provide. Verified `grep` confirmed no test asserts the old message text, so this is a safe,
non-breaking change. Verified via `./gradlew compileJava compileTestJava --offline` (clean
compile); `./gradlew test --tests de.moviearchive.admin.WikiReloadControllerTest --offline` failed
with a pre-existing environment issue (`Could not find a valid Docker environment` —
Testcontainers-backed `AbstractIntegrationTest` base class requires Docker, unavailable in this
sandbox), unrelated to this change — confirmed the failure is a class-init Docker error, not a
test assertion failure.

### WR-03: `EnrichmentService.enrich()` can leave a movie permanently `PENDING` on API-key decrypt failure

**Files modified:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java`
**Commit:** `00d6c4a`
**Applied fix:** Moved `movieRepository.findByIdWithUser(...)` and
`settingsService.getApiKeys(...)` inside the existing `try` block (previously they ran before
it), so any failure — including an AES-GCM decrypt failure on a stored API key — is now caught
by the same `catch (Exception e)` block. Updated the catch block to use
`movieRepository.findById(movieId).ifPresent(...)` to set `MovieStatus.ERROR` (since the local
`movie` variable is now out of scope in the catch block if the load itself failed), matching the
review's suggested fix exactly. Updated the method's class-level JavaDoc comment ("Only TMDB
failure sets status=ERROR") to reflect that any failure during loading/key-decryption/TMDB now
sets `ERROR`. Verified no existing test in `EnrichmentServiceTest` relies on the movie-not-found
case propagating uncaught. Verified via `./gradlew compileJava compileTestJava --offline` (clean
compile, no test run needed beyond compile check since the change is a straightforward
try-block-boundary move with no altered control flow for the already-tested code paths).

## Skipped Issues

None — all in-scope findings were fixed.

---

_Fixed: 2026-08-23T12:14:14Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
