---
phase: 13-wikidata-sparql-batch-lookup
reviewed: 2026-08-27T00:00:00Z
depth: standard
files_reviewed: 10
files_reviewed_list:
  - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
  - backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java
  - backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java
  - backend/src/main/java/de/moviearchive/bulkimport/dto/MatchedLine.java
  - backend/src/main/resources/application.properties
  - backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java
  - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java
  - backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
findings:
  critical: 2
  warning: 2
  info: 1
  total: 5
status: fixed
---

## Post-Review Fixes

**CR-01 and CR-02 fixed** in commit `cf35820` (fix(13): pace BulkImportService Pass 2 dispatch and
restore wiki cooldown default): Pass 2 now paces dispatches with `Thread.sleep(pacingDelayMs)` and
wraps each call in try/catch (mirroring Pass 1), and `wiki.retry.cooldown-days` default was
reverted from the leftover `0` to `30`. This also resolves WR-01 (same pacing fix limits
concurrent worker fan-out). WR-02 and IN-01 remain open as documented lower-severity follow-ups —
see their sections below for the accepted disposition and suggested fix.

Verified: `BulkImportServiceTest` (9/9), `BulkImportControllerTest` (14/14), `WikiReloadServiceTest`
(4/4), `WikiReloadServiceIntegrationTest` (5/5), `EnrichmentServiceTest` (4/4) all pass after the fix.

# Phase 13: Code Review Report

**Reviewed:** 2026-08-27
**Depth:** standard
**Files Reviewed:** 10
**Status:** issues_found

## Summary

Phase 13 replaces `WikipediaClient`'s per-movie two-REST-call Wikidata lookup with a single
batched SPARQL query (Plan 1), then threads the resulting `imdbId -> enwiki title` map through
`WikiReloadService.batchReload()` (Plan 2) and `BulkImportService`'s new two-pass enrichment
(Plan 3). Plan 1's `WikipediaClient` changes are well-tested and internally consistent — chunking,
empty-list short-circuiting, prefetch-map semantics, and 429 backoff sharing are all covered by
targeted WireMock tests and match their own javadoc. Plan 2's `WikiReloadService` restructuring is
clean: `batchReload()` stays synchronous per-movie inside its own single-threaded executor, so
none of the concurrency issues below apply to it.

Plan 3's `BulkImportService` restructuring, however, introduces two release-blocking regressions.
Its new "Pass 2" enrichment-dispatch loop removed the pacing/dispatch-spacing that the old
per-line loop had, and does so on top of a hard-capped `enrichmentExecutor` (core=2, max=5,
queue=50). For any bulk import with more than roughly 55 successfully-matched lines — an entirely
ordinary outcome for importing a real movie collection — this will throw an uncaught rejection
that aborts the batch mid-way and leaves it permanently incomplete. Separately, a config value
explicitly marked `TEMPORARY (dev-testing)` in `application.properties` was left un-reverted and
ships as the production default, silently disabling the cooldown-window protection this project
already built for exactly this class of hammering risk. Two further, lower-severity findings
concern a reintroduced request-burst risk and a documented invariant that can be silently violated
under transient TMDB failures.

## Critical Issues

### CR-01: BulkImportService Pass 2 can crash the batch for realistic import sizes (unbounded, unpaced async dispatch into a hard-capped executor)

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:144-147`
**Issue:**

```java
// Pass 2: enrich every matched line, threading the SAME resolved map into each call so
// the Wikipedia step skips a per-movie SPARQL call entirely.
for (UUID movieId : matchedMovieIds) {
    enrichmentService.enrich(movieId, resolvedTitles);
}
```

Before this phase, `enrichmentService.enrich(movieId)` was called *inline* inside Pass 1's
per-line loop, immediately before the loop's existing `Thread.sleep(pacingDelayMs)` — so
successive enrichment dispatches were naturally spaced roughly `pacingDelayMs` apart (default
1000ms). This phase moved enrichment entirely into a new Pass 2 loop that runs *after* Pass 1
completes, with **no delay between iterations at all**.

`enrichmentExecutor` (`backend/src/main/java/de/moviearchive/config/AsyncConfig.java:12-21`) is a
bounded `ThreadPoolTaskExecutor`: `corePoolSize=2`, `maxPoolSize=5`, `queueCapacity=50`, default
`AbortPolicy`. A JDK `ThreadPoolExecutor` does not grow beyond `corePoolSize` until its queue is
completely full, so in practice this pool absorbs roughly 2 (running) + 50 (queued) + 3 (extra
threads spun up once the queue fills) ≈ **55 in-flight tasks** before it starts rejecting
submissions with a `RejectedExecutionException` / `TaskRejectedException`.

Pass 2 submits all of `matchedMovieIds` in a tight, no-I/O loop — effectively instantaneously
relative to how long each enrichment task takes to drain (TMDB + OMDB + Wikipedia + Postgres +
OpenSearch, realistically hundreds of ms to seconds each). For a bulk import with more than ~55
successfully-matched lines — trivially reachable given `bulk-import.max-lines=5000` and an
ordinary personal-collection import — this loop **will** exceed the executor's capacity.

Unlike Pass 1 (`try { ... } catch (Exception e) { log.warn(...) }` per line), Pass 2 has **no
per-call try/catch**. The rejection exception propagates synchronously out of
`enrichmentService.enrich(...)`, out of the `for` loop, and out of `runImport()` itself (which has
no top-level try/catch around its body). The practical effect:
- `progressService.complete(batchId)` (the line immediately after the loop) never runs.
- Every matched line at or after the rejection point never gets enriched, and stays at
  `status=PENDING` forever — there is no retry path for this state.
- The SSE progress stream the frontend polls never signals completion for that batch.

This is a straightforward regression versus the previous behavior (which, by dispatching at
~1/sec, never came close to saturating the same executor) and defeats the batch-safety goal this
whole milestone exists to serve, for the enrichment-dispatch stage specifically.

**Fix:** Restore pacing between Pass 2 dispatches (mirror Pass 1's `Thread.sleep(pacingDelayMs)`
between iterations), and/or wrap each dispatch in its own try/catch so a rejection degrades one
line to a loggable failure instead of aborting the rest of the batch and skipping
`progressService.complete(batchId)`:

```java
for (int i = 0; i < matchedMovieIds.size(); i++) {
    try {
        enrichmentService.enrich(matchedMovieIds.get(i), resolvedTitles);
    } catch (Exception e) {
        log.warn("Bulk import: enrichment dispatch failed for movieId={}: {}",
                matchedMovieIds.get(i), e.getMessage());
    }
    if (i < matchedMovieIds.size() - 1) {
        try {
            Thread.sleep(pacingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
}
progressService.complete(batchId);
```
Also consider whether `enrichmentExecutor`'s capacity should be sized against
`bulk-import.max-lines`, or whether bulk-import enrichment should use its own executor.

---

### CR-02: `wiki.retry.cooldown-days` ships with a self-labeled "TEMPORARY" value that disables the cooldown protection in every environment

**File:** `backend/src/main/resources/application.properties:63-65`
**Issue:**

```properties
# Wiki batch-reload (Phase 8: cooldown window + inter-request pacing, D-04/D-08)
# TEMPORARY (dev-testing) — was 30, set to 0 so batch-reload can immediately re-process previously-failed movies against the new Wikidata-first lookup; revert to 30 when done testing
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:0}
```

The comment explicitly states this is a temporary dev-testing override that must be reverted to
`30` before shipping, but it was never reverted — this phase's SUMMARY files contain no mention of
restoring it. There is no `application-prod.properties`, and grepping `docker-compose.yml`,
`.env`, and `.env.example` for `WIKI_RETRY_COOLDOWN_DAYS` finds no override anywhere in this repo.
That means the effective default in every environment that doesn't set the env var explicitly is
**0 days** — i.e. `findEligibleForWikiReload` treats every movie missing wiki data as eligible on
*every single* `batchReload()` invocation, with zero cooldown throttling between runs. This
directly undoes the ENRICH-02 cooldown-window protection this project built specifically to avoid
hammering Wikipedia/Wikidata with repeat lookups for the same already-failed movie.

**Fix:** Revert the default back to 30 before merging, and remove the stale comment (or convert it
into a `WIKI_RETRY_COOLDOWN_DAYS=0` line in a local dev-only `.env` override instead of the
committed base properties file):

```properties
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}
```

## Warnings

### WR-01: Pass 2's unpaced, concurrent dispatch reintroduces a request-burst risk against TMDB/Wikipedia

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:144-147`, `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java:79-96`
**Issue:** Independent of CR-01's crash risk, even when the executor doesn't reject anything, up
to 5 `enrichmentExecutor` worker threads can now run concurrently as soon as Pass 2's loop
finishes submitting (previously bounded to roughly 1 new task/second by Pass 1's inline dispatch +
sleep). Each of those concurrent workers re-fetches TMDB detail from scratch in `doEnrich()`'s Step
1 (`EnrichmentService.java:81`) — a second, unpaced TMDB call per movie on top of the one
`resolveAndPersistImdbId()` already made in Pass 1 — and, for movies whose imdbId *did* resolve via
the SPARQL prefetch, goes on to fetch Wikipedia section content (`tryFetch`, up to 4 requests) via
`WikipediaClient.paceRequest()`. `paceRequest()`'s `Thread.sleep()` only throttles the *calling*
thread; it provides no cross-thread rate limiting until a 429 is actually observed and the shared
`backoffUntil` window kicks in. With up to 5 threads each independently pacing at ~1 req/s, the
aggregate request rate against `en.wikipedia.org`/TMDB during Pass 2 can spike well above the
single-thread rate this project's own comments describe as the safe ceiling
(`WikipediaClient.java:44-53`), for exactly the reason this whole phase's milestone exists to
prevent — just shifted from the Wikidata-lookup stage (fixed by Plan 1) to the content-fetch stage
(not addressed by Plan 2/3).
**Fix:** Same fix as CR-01 (pace Pass 2's dispatches) addresses this too — spacing dispatches by
`pacingDelayMs` keeps the number of enrichment tasks genuinely running concurrently low, close to
the previous behavior.

### WR-02: A transient `resolveAndPersistImdbId()` failure can cause `fetch()` to silently skip an available, accurate Wikidata match

**File:** `backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:162-183`, `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:231-244`
**Issue:** `resolveWikidataResult()`'s documented contract is: when a caller supplies a
`preResolvedTitles` map, a miss (`imdbId` absent, or `imdbId` itself null) "means already checked
by the caller's batch prefetch, do not re-query." That invariant assumes every matched line's
imdbId that *could* be resolved was actually included in the Pass 1.5 SPARQL batch. But
`resolveAndPersistImdbId()` swallows all exceptions and returns `null` on any TMDB failure
(network blip, transient TMDB error, etc.) — when that happens, the movie's imdbId is *not* added
to `imdbIdByMovieId`, so it is *not* included in the Pass 1.5 `resolveViaWikidataSparql()` call.
If `EnrichmentService.doEnrich()`'s own later, redundant TMDB detail call (Step 1) then succeeds
(a very plausible outcome — one transient failure followed by a successful retry, especially given
WR-01's now-unpaced concurrent TMDB volume), `movie.getImdbId()` is non-null by the time the
Wikipedia step runs, but that id was never actually checked against Wikidata. `fetch()` still
receives the non-null `preResolvedWikiTitles` map from Pass 1.5, looks up the (never-checked)
imdbId, gets a miss, and — per the documented "miss = already checked" contract — falls straight
through to the weaker candidate-URL guessing cascade instead of retrying (or at least attempting)
the accurate Wikidata lookup. This silently degrades wiki-data quality for exactly the movies most
likely to be affected by transient TMDB errors, with no log line or signal distinguishing this
case from a genuine Wikidata miss.
**Fix:** Either (a) have `resolveWikidataResult`/`fetch()` accept a way to distinguish "genuinely
absent from Wikidata" from "never checked", or (b) have `EnrichmentService.doEnrich()` skip its own
redundant TMDB call when `preResolvedWikiTitles != null` and the movie already has a persisted
imdbId, only falling back to a fresh TMDB call when the map was provided but the movie's imdbId is
still null — narrowing the window in which this drift can occur.

## Info

### IN-01: SPARQL `VALUES` clause built via raw string concatenation, no format validation of `imdbId`

**File:** `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java:291-299`
**Issue:** `resolveChunkViaWikidataSparql` builds the query's `VALUES` clause by wrapping each
`imdbId` in double quotes via plain string concatenation, with no escaping or shape validation.
The phase's own threat model (`13-01-PLAN.md`, T-13-01) already assessed and accepted this at low
severity on the grounds that `imdbId` values originate exclusively from TMDB's
`external_ids.imdb_id` field and are shape-validated as `tt\d+` upstream — a reasonable disposition
given the current call graph. Flagging only as a documentation/defense-in-depth note: nothing in
`WikipediaClient` itself enforces that shape at the point the query string is assembled, so if a
future caller ever threads a differently-sourced string through `resolveViaWikidataSparql()`
(user-supplied search text, a manually-entered IMDb URL fragment, etc.), the guarantee silently
stops holding.
**Fix:** A one-line defense-in-depth guard costs little and removes the dependency on "every
current and future caller only ever passes TMDB-sourced ids":
```java
List<String> filtered = imdbIds.stream()
        .filter(id -> id != null && id.matches("tt\\d+"))
        .distinct()
        .toList();
```

---

_Reviewed: 2026-08-27_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
