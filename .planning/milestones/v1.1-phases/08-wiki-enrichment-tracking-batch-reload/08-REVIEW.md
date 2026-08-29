---
phase: 08-wiki-enrichment-tracking-batch-reload
reviewed: 2026-08-22T23:17:36Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/admin/WikiReloadController.java
  - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
  - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
  - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
  - backend/src/main/java/de/moviearchive/movie/Movie.java
  - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
  - backend/src/main/resources/application.properties
  - backend/src/main/resources/db/migration/V8__add_wiki_last_attempted_at_to_movies.sql
  - backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java
  - backend/src/test/java/de/moviearchive/movie/EnrichmentIntegrationTest.java
  - backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java
  - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java
  - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
  - backend/src/test/resources/application-test.properties
findings:
  critical: 0
  warning: 3
  info: 2
  total: 5
status: issues_found
---

# Phase 8: Code Review Report

**Reviewed:** 2026-08-22T23:17:36Z
**Depth:** standard
**Files Reviewed:** 14
**Status:** issues_found

## Summary

Reviewed the Phase 8 wiki-enrichment-tracking + batch-reload implementation (commits `9d78f84`
and `ee3fc99`): `WikiReloadController`, `WikiReloadService`, the new `wikiReloadExecutor` bean,
the `MovieRepository.findEligibleForWikiReload` cooldown query, the `wiki_last_attempted_at`
migration/entity field, and the one-line `EnrichmentService` addition, plus their tests.

No critical/security-blocking defects found — the IDOR ownership check (403 on JWT/path
mismatch), the AES-GCM-encrypted-key handling, and the bounded-queue rejection path all behave
as documented and are exercised by tests. However, two structural defects undermine guarantees
the code's own comments and JavaDoc explicitly promise:

1. `WikiReloadService.retryWikipedia()`'s `@Transactional` is silently bypassed by
   self-invocation from `batchReload()` — the exact Spring AOP proxy pitfall this project's own
   `CLAUDE.md` warns about for `@Async`/`@Retryable`, just recurring here with `@Transactional`.
2. `wikiReloadExecutor` is a single **global** bounded thread pool shared by all users, not
   scoped per user, so the 503 rejection ("already in progress for this user") and the queued-
   second-trigger behavior can actually be caused by a completely unrelated user's batch job —
   contradicting both the controller's error message and the apparent per-user intent of T-08-02.

A third finding revisits a pre-existing gap in `EnrichmentService.enrich()` (not part of this
phase's diff, but present in the reviewed file): two calls that can throw happen before the
try/catch that sets `MovieStatus.ERROR`, so a decrypt failure on a stored API key leaves a movie
stuck at `PENDING` forever with no error signal.

## Warnings

### WR-01: `retryWikipedia()`'s `@Transactional` has no effect when called from `batchReload()` (self-invocation)

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:100-125` (call site at line 109), method declared at `:59-90`

**Issue:** `batchReload()` calls `retryWikipedia(movie)` as a plain unqualified method call
(`this.retryWikipedia(movie)`) from within the same class instance. Spring's `@Transactional`
(and `@Async`, `@Retryable`) are implemented via proxy-based AOP — only calls that go through
the Spring-managed proxy (i.e. calls from a *different* bean, or through a self-injected proxy
reference) are intercepted. A same-class, unqualified call bypasses the proxy entirely, so the
`@Transactional` annotation on `retryWikipedia()` is silently a no-op whenever it's reached via
`batchReload()` — which is its only real production call path (`WikiReloadController` never
calls `retryWikipedia()` directly).

This is the identical anti-pattern this project's own `CLAUDE.md` explicitly documents and
warns against for `@Async`/`@Retryable` self-invocation ("Spring proxy is bypassed — annotations
have no effect"). It applies equally to `@Transactional`, and the class's own JavaDoc
(`WikiReloadService.java:24-25`, "only the per-movie retryWikipedia() is @Transactional") states
a guarantee that does not actually hold at the real call site.

Currently this is not causing an observable production bug only because `retryWikipedia()`
happens to persist state via explicit `movieRepository.save(movie)` calls rather than relying
on transactional dirty-checking, and `IndexingService.index()` only calls
`movie.getUser().getId()` (safe to read off an uninitialized Hibernate proxy without a session).
But the two `save()` calls in the success branch (wiki-fields save, then indexed_at save) are
**not** atomic with each other as the code/comments imply — they are two independent
auto-committing repository calls, not one transaction. Any future change that relies on the
`@Transactional` boundary (e.g. adding a field that depends on dirty-checking flush, or
widening what `IndexingService.index()` reads off `movie.getUser()`) will silently break or
throw `LazyInitializationException` with no compiler or test signal.

**Fix:** Make the call go through the Spring proxy, e.g. self-inject a lazy reference to the
bean's own proxy and invoke through it:
```java
@Service
@Slf4j
public class WikiReloadService {

    private final WikiReloadService self; // proxy, injected lazily to avoid circular-init issues

    public WikiReloadService(MovieRepository movieRepository,
                              WikipediaClient wikipediaClient,
                              IndexingService indexingService,
                              @Lazy WikiReloadService self) {
        ...
        this.self = self;
    }

    @Async("wikiReloadExecutor")
    public void batchReload(UUID userId) {
        ...
        self.retryWikipedia(movie); // now goes through the proxy — @Transactional applies
        ...
    }
}
```
Alternatively, extract `retryWikipedia()` into a separate collaborator bean that `WikiReloadService`
depends on, which is the cleaner long-term fix and avoids the self-injection pattern entirely.

---

### WR-02: `wikiReloadExecutor` is global, not per-user — misleading 503 message and cross-user request interference

**File:** `backend/src/main/java/de/moviearchive/config/AsyncConfig.java:30-39`, `backend/src/main/java/de/moviearchive/admin/WikiReloadController.java:75-83`

**Issue:** `wikiReloadExecutor` (core=1/max=1/queue=1) is a single Spring bean shared across the
entire application, not scoped per user. `WikiReloadController`'s class JavaDoc and the
`handleTaskRejected` 503 response both phrase the bounded-queue rejection as being about "this
user" (`"A wiki-reload is already in progress for this user; try again shortly."`), but the
1-running + 1-queued capacity is actually a whole-application limit. In a scenario with more
than one active user (the data model is explicitly multi-user-capable — `movies-{userId}`
per-user OpenSearch indices, per-user ownership checks), User A triggering a reload can cause
User B's *completely unrelated* trigger to be queued behind A's job, or rejected with a 503 that
claims a conflict specific to User B when none exists. This also means the queued run in
`shouldQueueSecondTrigger_whileFirstRunInProgress` and the rejection in
`shouldReject_whenThirdTriggerExceedsQueueCapacity` in `WikiReloadControllerTest` only
demonstrate same-user overlap by coincidence of the test using a single user — they do not
actually verify per-user isolation, because no such isolation exists in the implementation.

Given `CLAUDE.md`'s "single-user-first" scope for v1, this is unlikely to bite in the app's
actual current usage pattern, but it's a real behavioral gap versus what the code and its own
error message claim, and it will become an actual multi-tenant bug the moment a second user
exists.

**Fix:** Either scope the concurrency guard per user (e.g. a `ConcurrentHashMap<UUID, AtomicBoolean>`
or per-user `Semaphore` checked before submitting to a shared executor with a larger pool), or —
if a single global sequential pacer really is the intended design even across users — update the
503 message to accurately describe a global, not per-user, conflict:
```java
return ResponseEntity.status(503).body(Map.of(
        "message", "A wiki-reload batch is already in progress; try again shortly."));
```

---

### WR-03: `EnrichmentService.enrich()` can leave a movie permanently `PENDING` on API-key decrypt failure

**File:** `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:53-60`

**Issue:** `movieRepository.findByIdWithUser(...)` (line 53) and `settingsService.getApiKeys(email)`
(line 57) both execute *before* the `try` block that begins at line 60 and whose `catch` (lines
132-137) is the only place `MovieStatus.ERROR` gets set. `settingsService.getApiKeys()` calls
`encryptionService.decrypt(...)` on the user's stored TMDB/OMDB keys
(`backend/src/main/java/de/moviearchive/settings/SettingsService.java:106,110`), which can throw
(e.g. AES-GCM auth-tag failure after an `ENCRYPTION_MASTER_KEY` rotation, or a corrupted stored
ciphertext). Because this call sits outside the try/catch, any such failure propagates out of
the `@Async` method uncaught. No `AsyncUncaughtExceptionHandler` is configured anywhere in
`AsyncConfig` (or elsewhere in the reviewed files), so Spring's default handler just logs the
exception — the movie is left at `MovieStatus.PENDING` forever, with no error status, no retry
path, and no user-visible indication that enrichment failed.

**Fix:** Move the movie/key lookups inside the try block (or add a narrower try/catch around them)
so any failure also transitions the movie to `ERROR`:
```java
try {
    Movie movie = movieRepository.findByIdWithUser(movieId)
            .orElseThrow(() -> new IllegalStateException("Movie not found for enrichment: " + movieId));
    String email = movie.getUser().getEmail();
    Map<String, Object> keys = settingsService.getApiKeys(email);
    String tmdbKey = (String) keys.get("tmdb");

    // === Step 1: TMDB detail (MANDATORY) ===
    ...
} catch (Exception e) {
    log.error("Enrichment failed for movieId={} — setting status=ERROR", movieId, e);
    movieRepository.findById(movieId).ifPresent(m -> {
        m.setStatus(MovieStatus.ERROR);
        movieRepository.save(m);
    });
}
```
(Note the `orElseThrow` case still can't set status on a movie it never loaded — that's expected
and fine; the important fix is that `getApiKeys()` failures no longer escape untracked.)

## Info

### IN-01: Enum literal embedded directly in JPQL instead of a bound parameter

**File:** `backend/src/main/java/de/moviearchive/movie/MovieRepository.java:65-68`

**Issue:** `findEligibleForWikiReload` hardcodes the enum as a fully-qualified JPQL literal
(`m.status = de.moviearchive.movie.MovieStatus.SUCCESS`) rather than binding it as a `:status`
parameter like the query's own `:userId`/`:cutoff`. It works, but it's inconsistent with every
other filter in the same query and in this repository, isn't validated by the Java compiler
(a package/class rename silently breaks it only at query-parse time), and is harder to read.

**Fix:**
```java
@Query("SELECT m FROM Movie m WHERE m.user.id = :userId AND m.wikiUrl IS NULL " +
       "AND m.status = :status " +
       "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
List<Movie> findEligibleForWikiReload(@Param("userId") UUID userId,
                                       @Param("status") MovieStatus status,
                                       @Param("cutoff") Instant cutoff);
```

### IN-02: No operability signal when a whole `batchReload()` run fails before processing any movie

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java:100-104`

**Issue:** If `movieRepository.findEligibleForWikiReload(...)` itself throws (e.g. a transient DB
outage), the exception propagates out of the fire-and-forget `@Async` method and is silently
swallowed by Spring's default `SimpleAsyncUncaughtExceptionHandler` (log-only). The triggering
HTTP request already returned 202 before this point, so there is no way for a caller — or an
operator — to learn that the entire batch never started. Not a correctness bug (the design is
intentionally fire-and-forget), but worth a log-level bump to `error` at minimum, or a metric/counter,
for future observability.

---

_Reviewed: 2026-08-22T23:17:36Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
