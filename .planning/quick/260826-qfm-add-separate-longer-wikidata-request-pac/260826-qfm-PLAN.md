---
phase: quick
plan: 260826-qfm
type: execute
wave: 1
depends_on: []
files_modified:
  - backend/src/main/resources/application.properties
  - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
  - backend/src/test/resources/application-test.properties
autonomous: true
requirements: []

must_haves:
  truths:
    - "tryFetchViaWikidata()'s two paced calls (search + sitelinks) sleep wikidataRequestPacingMs (default 3000ms), independent from requestPacingMs (still 1000ms, default from wikipedia.request-pacing-ms) used by tryFetch()/tryFetchViaSearch()/fetchSection()"
    - "The no-arg paceRequest() delegates to the new paceRequest(long delayMs) overload — the backoffUntil-wait check exists in exactly one place, not duplicated"
    - "WikipediaClientTest's full suite still passes, and both 429-backoff regression tests' elapsed>=950ms floor is satisfied purely by the shared backoffUntil wait, not by an unrelated fixed Wikidata pacing sleep"
  artifacts:
    - path: "backend/src/main/resources/application.properties"
      provides: "New wikidata.request-pacing-ms property (default 3000ms via WIKIDATA_REQUEST_PACING_MS), placed near wikidata.base-url"
    - path: "backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java"
      provides: "New wikidataRequestPacingMs @Value field, paceRequest(long delayMs) overload, both tryFetchViaWikidata() call sites updated to use it"
    - path: "backend/src/test/resources/application-test.properties"
      provides: "wikidata.request-pacing-ms=0 test-suite-wide override (mirrors existing wikipedia.request-pacing-ms=0), keeping the suite fast and the 429-regression tests' timing assertions meaningful"
  key_links:
    - from: "wikidata.request-pacing-ms property"
      to: "WikipediaClient.wikidataRequestPacingMs field"
      via: "Spring @Value binding"
      pattern: "controls the sleep duration inside paceRequest(wikidataRequestPacingMs) at both tryFetchViaWikidata() call sites"
    - from: "paceRequest() no-arg"
      to: "paceRequest(long delayMs) overload"
      via: "direct delegation: paceRequest() calls paceRequest(requestPacingMs)"
      pattern: "single shared backoffUntil-wait implementation reused by both the Wikipedia-only pacing and the new Wikidata pacing"
---

<objective>
Give the two `tryFetchViaWikidata()` calls inside `WikipediaClient` their own, longer request-pacing value (`wikidata.request-pacing-ms`, default 3000ms) instead of sharing `wikipedia.request-pacing-ms` (1000ms) with every en.wikipedia.org call. The shared backoff/429-handling machinery (`backoffUntil`, `recordRateLimited`) is untouched — only the fixed per-request sleep duration becomes parameterizable, via a new `paceRequest(long delayMs)` overload that the existing no-arg `paceRequest()` now delegates to.

Purpose: A live batch-reload run hit wikidata.org's anonymous rate limiter after only ~3 movies (2 Wikidata calls each) at the current shared 1000ms pace — wikidata.org's limiter is stricter than en.wikipedia.org's (12-RESEARCH.md Pitfall 1: a live burst of just 2 unpaced requests tripped it). en.wikipedia.org calls keep their proven 1000ms pace unchanged.
Output: `wikidata.request-pacing-ms` property + `wikidataRequestPacingMs` field wired into both Wikidata call sites; existing Phase-12 Wikidata tests still green with their timing assertions intact and fast.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
@backend/src/main/resources/application.properties
@backend/src/test/resources/application-test.properties
@backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add wikidata.request-pacing-ms and wire it into tryFetchViaWikidata()</name>
  <files>backend/src/main/resources/application.properties, backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java</files>
  <action>
In `backend/src/main/resources/application.properties`, immediately after the existing line
`wikidata.base-url=${WIKIDATA_BASE_URL:https://www.wikidata.org}` (currently line 51, directly
above the `wikipedia.request-pacing-ms` comment block), insert a new comment + property:

```
# Separate, longer pacing for the two tryFetchViaWikidata() calls (search + sitelinks) — kept
# independent from wikipedia.request-pacing-ms below because wikidata.org's anonymous rate
# limiter is stricter than en.wikipedia.org's: a live batch-reload run hit it after only ~3
# movies (2 Wikidata calls each) at the shared 1000ms pace (12-RESEARCH.md Pitfall 1).
wikidata.request-pacing-ms=${WIKIDATA_REQUEST_PACING_MS:3000}
```

Do not change the existing `wikipedia.request-pacing-ms=${WIKIPEDIA_REQUEST_PACING_MS:1000}` line
or its comment, and do not touch `wiki.retry.pacing-delay-ms` or `bulk-import.pacing-delay-ms`.

In `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java`:

1. Directly after the `requestPacingMs` field declaration (the `@Value("${wikipedia.request-pacing-ms:1000}") private long requestPacingMs;` block), add a new field with its own javadoc:

```java
    /**
     * Minimum delay before EVERY outbound Wikidata API call inside {@link #tryFetchViaWikidata},
     * kept separate from and longer than {@link #requestPacingMs}: a live production batch-reload
     * run tripped wikidata.org's anonymous rate limiter after only ~3 movies (2 Wikidata calls
     * each) at the shared 1000ms pace — wikidata.org's limiter is stricter than en.wikipedia.org's
     * (12-RESEARCH.md: a burst of just 2 requests tripped it in live testing).
     */
    @Value("${wikidata.request-pacing-ms:3000}")
    private long wikidataRequestPacingMs;
```

2. Replace the existing `private void paceRequest()` method (the one containing the
`backoffUntil`/`Duration remaining`/`sleepQuietly(requestPacingMs)` logic) with a no-arg version
that delegates, plus a new parameterized overload carrying the SAME backoff-wait logic unchanged
— just replace the hardcoded `requestPacingMs` sleep argument with the new `delayMs` parameter:

```java
    private void paceRequest() {
        paceRequest(requestPacingMs);
    }

    /**
     * Same shared backoffUntil-wait as the no-arg {@link #paceRequest()}, parameterized on the
     * fixed per-request sleep applied after any backoff wait. Used by {@link
     * #tryFetchViaWikidata} with {@link #wikidataRequestPacingMs} so Wikidata calls pace
     * themselves independently from (and slower than) the en.wikipedia.org calls, which keep
     * using the no-arg overload unchanged.
     */
    private void paceRequest(long delayMs) {
        Instant waitUntil = backoffUntil.get();
        Duration remaining = Duration.between(Instant.now(), waitUntil);
        if (!remaining.isNegative() && !remaining.isZero()) {
            log.warn("Wikipedia rate-limit backoff in effect — waiting {}s before next request", remaining.toSeconds());
            sleepQuietly(remaining.toMillis());
        }
        sleepQuietly(delayMs);
    }
```

Do not change `sleepQuietly()`, `recordRateLimited()`, or the `backoffUntil` field itself — only
how the sleep duration reaches this shared wait logic changes.

3. Inside `tryFetchViaWikidata(String imdbId)`, change the two bare `paceRequest();` calls (one
immediately before the `wikidataWebClient.get()...srsearch=haswbstatement:P345` search call, one
immediately before the `wikidataWebClient.get()...sitelinks/enwiki` call) to
`paceRequest(wikidataRequestPacingMs);`. Leave every other `paceRequest();` call site in the file
(inside `tryFetch()`, `tryFetchViaSearch()`, `fetchSection()`) exactly as the bare no-arg call —
those keep using `requestPacingMs` (1000ms) via the no-arg overload's delegation.
  </action>
  <verify>
    <automated>cd /Users/simonreich/git/private/movie-archive/backend && ./gradlew compileJava -q && test "$(grep -c 'private long wikidataRequestPacingMs' src/main/java/de/moviearchive/enrichment/WikipediaClient.java)" = "1" && test "$(grep -c 'private void paceRequest(long delayMs)' src/main/java/de/moviearchive/enrichment/WikipediaClient.java)" = "1" && test "$(grep -c 'paceRequest(wikidataRequestPacingMs)' src/main/java/de/moviearchive/enrichment/WikipediaClient.java)" = "2" && test "$(grep -c 'wikidata.request-pacing-ms=\${WIKIDATA_REQUEST_PACING_MS:3000}' src/main/resources/application.properties)" = "1"</automated>
  </verify>
  <done>application.properties has the new wikidata.request-pacing-ms property (default 3000) near wikidata.base-url; WikipediaClient.java compiles with a new wikidataRequestPacingMs field, a paceRequest(long delayMs) overload sharing the unmodified backoffUntil-wait logic, a no-arg paceRequest() that delegates to paceRequest(requestPacingMs), and exactly the two tryFetchViaWikidata() call sites updated to paceRequest(wikidataRequestPacingMs) — all other paceRequest() call sites unchanged.</done>
</task>

<task type="auto">
  <name>Task 2: Keep the test suite fast and confirm Phase-12 Wikidata tests still pass</name>
  <files>backend/src/test/resources/application-test.properties</files>
  <action>
In `backend/src/test/resources/application-test.properties`, immediately after the existing line
`wikipedia.request-pacing-ms=0` (currently the last line in the file, with its "fast default for
the suite" comment above it), add:

```

# Per-request Wikidata API pacing (WikipediaClient.tryFetchViaWikidata) — fast default for the
# suite, same rationale as wikipedia.request-pacing-ms above. Real value (3000ms) only matters
# against the live Wikidata API; tests hit WireMock and don't need to wait for it. Keeping this
# at 0 also preserves the intent of the 429-backoff regression tests in WikipediaClientTest:
# their elapsed>=950ms floor must come from the shared backoffUntil wait alone, not from an
# unrelated fixed per-request Wikidata sleep.
wikidata.request-pacing-ms=0
```

This is required because without it, the new 3000ms production default would leak into every
`WikipediaClientTest` test that exercises `tryFetchViaWikidata()` (imdbId non-null): each of the
two Wikidata `paceRequest()` calls that actually fire would add up to 3 real seconds of sleep,
inflating `shouldReturnResult_viaWikidata_whenImdbIdMatchesP345` and
`shouldFallThroughToCascade_whenWikidataItemHasNoEnwikiSitelink` by ~6s each, and — more
importantly — `shouldHonorRetryAfterBackoff_onWikidataCall`'s `elapsed >= 950ms` assertion would
still numerically pass but for the wrong reason (an unrelated fixed 3000ms sleep instead of
solely the shared `backoffUntil` wait it exists to prove), silently defeating that regression
test's actual purpose. Do not touch `WikipediaClientTest.java` itself, its WireMock stubs, or any
existing assertion — with this properties-only fix, no test file changes are expected.

Then run the full `WikipediaClientTest` suite and confirm all 7 existing tests pass unmodified,
completing quickly (a few seconds, not 30+), and that both 429-backoff regression tests'
`elapsed >= 950ms` floor is now satisfied purely by the shared backoff wait. If — contrary to
expectation — a test still fails or a timing floor is no longer satisfiable, adjust ONLY that
specific timing assertion's numeric floor (never remove or weaken it to a tautology, never touch
the WireMock stub/fixture structure) so it still proves backoff actually occurred.
  </action>
  <verify>
    <automated>cd /Users/simonreich/git/private/movie-archive/backend && test "$(grep -c 'wikidata.request-pacing-ms=0' src/test/resources/application-test.properties)" = "1" && ./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"</automated>
  </verify>
  <done>application-test.properties has wikidata.request-pacing-ms=0 mirroring wikipedia.request-pacing-ms=0; the full WikipediaClientTest suite (all 7 tests, including both 429-backoff regression tests) passes, runs fast, and no assertion was weakened.</done>
</task>

</tasks>

<verification>
Run the full backend test suite to confirm no regressions outside WikipediaClientTest:

```bash
cd /Users/simonreich/git/private/movie-archive/backend && ./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest" --tests "de.moviearchive.enrichment.*"
```

Confirm the diff is scoped to exactly the three intended files:

```bash
cd /Users/simonreich/git/private/movie-archive && git diff --stat backend/src/main/resources/application.properties backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java backend/src/test/resources/application-test.properties
```

Confirm no other Wikipedia-only call sites or callers were touched:

```bash
cd /Users/simonreich/git/private/movie-archive && git diff --stat -- backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java
```

Expected: empty output (no changes to either file).
</verification>

<success_criteria>
- `wikidata.request-pacing-ms` (default 3000ms) exists in application.properties, independent from `wikipedia.request-pacing-ms` (still 1000ms)
- `WikipediaClient` has a `wikidataRequestPacingMs` field and a `paceRequest(long delayMs)` overload; the no-arg `paceRequest()` delegates to it with `requestPacingMs`
- Both `tryFetchViaWikidata()` call sites use `paceRequest(wikidataRequestPacingMs)`; every other call site is untouched
- `backoffUntil`/`recordRateLimited()` logic is byte-for-byte unchanged — only how the sleep duration is supplied changed
- `WikiReloadService.java` and `EnrichmentService.java` have zero diff
- Full `WikipediaClientTest` suite passes, including both 429-backoff regression tests, with their `elapsed >= 950ms` assertions unweakened and now attributable purely to the shared backoff wait
</success_criteria>

<output>
After completion, create `.planning/quick/260826-qfm-add-separate-longer-wikidata-request-pac/260826-qfm-SUMMARY.md`
</output>
