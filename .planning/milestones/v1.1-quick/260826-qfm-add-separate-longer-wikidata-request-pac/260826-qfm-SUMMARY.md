---
status: complete
phase: quick
plan: 260826-qfm
subsystem: backend
tags: [wikidata, wikipedia, rate-limiting, request-pacing]
dependency_graph:
  requires: []
  provides: ["wikidata.request-pacing-ms property", "WikipediaClient.paceRequest(long) overload"]
  affects: [WikipediaClient.tryFetchViaWikidata()]
tech_stack:
  added: []
  patterns: ["parameterized pacing delay via paceRequest(long delayMs) overload, no-arg paceRequest() delegates to it"]
key_files:
  modified:
    - backend/src/main/resources/application.properties
    - backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java
    - backend/src/test/resources/application-test.properties
completed: 2026-08-29
tasks_completed: 1
outcome: complete
---

## What happened

Gave the two `tryFetchViaWikidata()` calls in `WikipediaClient` their own, longer request-pacing value instead of sharing `wikipedia.request-pacing-ms` (1000ms) with every en.wikipedia.org call. A live batch-reload run had hit wikidata.org's stricter anonymous rate limiter after only ~3 movies at the shared 1000ms pace.

- Added `wikidata.request-pacing-ms=${WIKIDATA_REQUEST_PACING_MS:3000}` in `backend/src/main/resources/application.properties`, next to `wikidata.base-url`.
- Added `wikidataRequestPacingMs` `@Value`-injected field and a `paceRequest(long delayMs)` overload in `WikipediaClient.java`; the existing no-arg `paceRequest()` now delegates to it with `requestPacingMs`, keeping the shared `backoffUntil`-wait logic in exactly one place.
- Both `tryFetchViaWikidata()` call sites (search + sitelinks) now call `paceRequest(wikidataRequestPacingMs)`.
- `backend/src/test/resources/application-test.properties` sets `wikidata.request-pacing-ms=0` (mirroring the existing `wikipedia.request-pacing-ms=0` test override) to keep the suite fast.

**Verified live in current code:** `application.properties` line 71 (`wikidata.request-pacing-ms=${WIKIDATA_REQUEST_PACING_MS:3000}`), and `WikipediaClient.java` has the `wikidataRequestPacingMs` field, the `paceRequest(long)` overload, and both Wikidata call sites using it.
