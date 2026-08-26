# Phase 12: Wikidata-based Wikipedia lookup - Research

**Researched:** 2026-08-26
**Domain:** External API integration (Wikidata search + REST API) inside an existing Spring Boot `WebClient` client
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Resolution order**
- **D-01:** New Wikidata P345 lookup is tried first inside `WikipediaClient.fetch()`, using the already-populated `movie.imdbId`. Falls through to the existing 6/10-step candidate-URL cascade only when the IMDb ID is missing, Wikidata has no P345 match, or the matched Wikidata item has no `enwiki` sitelink. — **Reversibility:** reversible — purely additive to `fetch()`, no schema change.
- **D-02:** OMDB is out of scope for this phase. Investigated whether OMDB could substitute part of the Wikipedia enrichment: it cannot — OMDB's `Plot` field is IMDb-sourced and the API contract (`.claude/api-contracts.md`) shows OMDB never maps to `wiki_plot`/`wiki_summary`/`wiki_critics`. OMDB and Wikipedia enrichment stay fully independent, no pipeline reordering.

**Rollout scope**
- **D-03:** The new Wikidata-first lookup applies automatically everywhere `WikipediaClient.fetch()` is called — save flow (`EnrichmentService`), manual per-film retry (`WikiReloadService.retryWikipedia`, Phase 9), and batch-reload (`WikiReloadService.batchReload`, Phase 8). One client, one method — no path is special-cased or excluded.

**Backfill**
- **D-04:** No active/forced backfill run for the ~630 previously-failed bulk-import films is part of this phase. Explicitly decided against ("das machen wir auf keinen Fall") — those films are intentionally left as real-world test material: once Phase 12 ships, the user will manually trigger retries/batch-reload on them to observe the new lookup working in practice, rather than have Phase 12 push a bulk re-enrichment itself.

**Dev visibility (Wikidata vs. fallback)**
- **D-05:** Add a temporary, human-readable log of how each Wikipedia lookup was resolved — driven by the user's frustration with having zero insight into what the Wikipedia enrichment step is doing (no progress indicator, no visibility). Requirements, precisely as narrowed down during discussion:
  - **Not** the normal application log / terminal / Docker log output — a **separate** artifact the user can open and read on demand.
  - **Not** JSON or any structured/machine format — plain, easily human-readable lines.
  - One line per Wikipedia enrichment attempt, e.g. `Inception (2010): found via Wikidata` / `Inception (2010): fallback candidate #3 (Inception_(2010_film))` / `Inception (2010): not found`.
  - Explicitly framed as **temporary/dev-only** — intended to be removed again later without leaving residue (e.g. no permanent DB column, no permanent UI element). A plain appended text/log file is the right shape; keep it easy to strip out.
  - Purpose is analytical/debugging only, not a user-facing feature.

### Claude's Discretion
- Exact Wikidata query mechanism (SPARQL endpoint vs. `wbgetentities`/`wbsearchentities` REST API vs. Special:EntityData) — technical choice for research/planning. **Resolved by this research: see Standard Stack / Alternatives Considered — recommend `haswbstatement` CirrusSearch + REST `sitelinks` endpoint.**
- Exact file path/name and rotation behavior of the temporary resolution log (D-05) — as long as it's a separate, human-readable, easily-removable artifact.
- Whether the new Wikidata call reuses `WikipediaClient`'s existing `paceRequest()`/429-backoff machinery or needs its own — technical integration detail. **Resolved by this research: reuse — see Summary and Pitfall 1 (live-confirmed rate limiting on `wikidata.org`).**

### Deferred Ideas (OUT OF SCOPE)
- Active backfill/re-enrichment trigger for the ~630 films missing Wikipedia data — explicitly rejected for this phase (see D-04); the user wants to observe the new lookup organically via manual retry/existing batch-reload instead.
- Todos matched against this phase by keyword (`enhance bulk import batch detail page`, `support real CSV parsing for bulk import`, `show progress indicator for Wikipedia batch-reload`) were reviewed and explicitly NOT folded — all three are Bulk Import (Phase 8–11) topics, out of scope for this independent WikipediaClient phase.
</user_constraints>

## Summary

The goal is to make `WikipediaClient.fetch()` try a direct, unambiguous Wikidata lookup before its existing 6/10-step candidate-URL cascade. The mechanism is a **two-call chain against `wikidata.org`**, both using the exact same request/response idiom (`WebClient` + `JsonNode`) already used everywhere in this client:

1. **Resolve IMDb ID → Wikidata item** via the MediaWiki search API's `haswbstatement` CirrusSearch keyword: `GET /w/api.php?action=query&list=search&srsearch=haswbstatement:P345={imdbId}&format=json` on `https://www.wikidata.org`. This is the same `action=query&list=search` family the client already calls against `en.wikipedia.org` for its search-fallback step — no new request idiom.
2. **Resolve Wikidata item → English Wikipedia article title** via the stable (GA since 2024-11-11) Wikibase REST API: `GET /w/rest.php/wikibase/v1/entities/items/{qid}/sitelinks/enwiki`. Returns `{"title": "...", "url": "..."}` on success, plain **HTTP 404** if the item has no `enwiki` sitelink.

Both calls were **verified live in this research session** (see Sources) against a real film (Inception, `tt1375666` → `Q25188` → sitelink title `"Inception"`), confirming exact response shapes and the 404-on-no-sitelink behavior. A bogus/nonexistent IMDb ID search correctly returns `"totalhits":0` with an empty `search` array — the clean "no Wikidata match" signal to fall through to the existing cascade.

Once step 2 yields a resolved article title, feed it straight into the **existing private `tryFetch(pageTitle)` method** — no new section-parsing or wikitext-cleaning code is needed; that method already re-resolves redirects (`redirects=1`) and extracts summary/plot/critics exactly as today.

**Critically, a live test in this session hit Wikimedia's anonymous-tier rate limiter ("You are making too many requests to the API") on a single ad-hoc `curl` burst against `wikidata.org`** — direct, first-hand confirmation that `wikidata.org` enforces the same kind of anonymous-API throttling the existing `WikipediaClient` javadoc already documents for `en.wikipedia.org`. The new Wikidata calls MUST go through the existing `paceRequest()` / `backoffUntil` machinery (Claude's discretion in CONTEXT.md D-05 — this research recommends **reuse**, not a separate mechanism) to avoid re-introducing the exact 429 cascade Phase 8 was built to prevent.

**Primary recommendation:** Add a private `tryFetchViaWikidata(String imdbId)` method to `WikipediaClient`, called first in `fetch()` when `imdbId != null`. It performs the two calls above (both paced/backed-off via the existing shared machinery), and on a resolved title delegates to the existing `tryFetch(slug)`. Any failure at any point (null imdbId, no search hit, 404 sitelink, network error) returns `Optional.empty()` and `fetch()` proceeds unchanged into `buildCandidates()`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Wikidata IMDb→QID resolution | API/Backend (`WikipediaClient`) | — | Pure server-side external API integration; no client/UI involvement |
| Wikidata QID→enwiki sitelink resolution | API/Backend (`WikipediaClient`) | — | Same client, same method chain |
| Wikipedia article content extraction (plot/summary/critics) | API/Backend (`WikipediaClient.tryFetch`) | — | Unchanged — reused as-is regardless of how the page title was resolved |
| Dev-visibility resolution log (D-05) | API/Backend (`WikipediaClient`, file append) | — | Server-local temporary artifact; no DB/UI involvement per D-05 |
| Rollout to save-flow / manual retry / batch-reload | API/Backend (`EnrichmentService`, `WikiReloadService`) | — | Unchanged callers — D-03 requires zero caller-side changes; all consume `WikipediaClient.fetch()` unmodified |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework:spring-webflux` (`WebClient`) | already on classpath (Spring Boot 3.5.0 BOM) | HTTP calls to Wikidata | Already the exact client instance (`this.webClient`) `WikipediaClient` uses for `en.wikipedia.org` — same `baseUrl`-relative pattern extends naturally to a second base URL bean or a raw absolute-URI call |
| `com.fasterxml.jackson.databind.JsonNode` | already on classpath (Spring Boot BOM) | Parse Wikidata JSON responses | Identical `.path()`-chain idiom already used for every other Wikipedia API response in this class |

**No new dependencies required.** Both calls use plain JSON over HTTP with the same client/parsing stack already present. `spring-retry` / `spring-aspects` (already in `build.gradle.kts` per CLAUDE.md) are NOT to be applied to the new method itself, matching the existing rule that `fetch()`'s internal steps catch-and-return-`Optional.empty()` rather than throw-and-retry (see Pitfall 2).

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| n/a | — | — | No supporting libraries needed — this phase is two additional HTTP calls inside an existing client, not a new integration surface |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `haswbstatement` CirrusSearch (`action=query&list=search`) + REST `sitelinks` endpoint | SPARQL query to `https://query.wikidata.org/sparql` joining P345 + sitelink in one round-trip | **Not recommended.** Fewer HTTP calls (1 vs 2), but: (a) different host with its own courtesy limits — WDQS enforces 60s query timeout / 5 concurrent queries per IP, a policy tuned for heavy analytical queries, not lightweight point lookups; (b) different response shape (SPARQL `bindings` JSON, not the `.path()`-friendly shape used everywhere else in this file) — would require a second, inconsistent parsing idiom; (c) requires hand-building a SPARQL query string (injection-adjacent string concatenation for the IMDb ID) vs. plain query-param substitution. The two-call MediaWiki-API approach stays on `wikidata.org` (same host family/software as `en.wikipedia.org`, same auth/etiquette rules already handled) and reuses the exact JSON-parsing convention already in this file. |
| REST API `/entities/items/{id}/sitelinks/enwiki` | Legacy `action=wbgetentities&props=sitelinks&sitefilter=enwiki` | Both verified working live in this session and return equivalent data (`wbgetentities` wraps in `entities.{qid}.sitelinks.enwiki.title`; REST returns a flat `{title, url}` with the canonical URL already assembled). **REST API recommended** — flatter response (fewer `.path()` hops), and it directly returns `wikipedia_url` (`"url"` field) without needing to reconstruct it, unlike `wbgetentities`. `wbgetentities` remains a documented fallback if the REST API endpoint is ever deprecated/unavailable, since it is the longer-established `action=` API family. |
| CirrusSearch `haswbstatement:P345=...` | `action=wbsearchentities` | Not applicable — `wbsearchentities` searches item **labels** (text search), not statement/property values; it cannot resolve "which item has IMDb ID X". `haswbstatement` is Wikidata's documented, purpose-built mechanism for exactly this external-ID → item lookup. |

**Installation:**
```
No new dependencies. Both endpoints are called via the existing WebClient/JsonNode stack.
```

**Version verification:** N/A — no package versions to verify; this is a REST/JSON-over-HTTP integration against a public Wikimedia API, not a library dependency. The Wikibase REST API's `v1` surface reached General Availability 2024-11-11 [CITED: lists.wikimedia.org Wikibase REST API announcement thread] and its exact endpoint behavior was independently confirmed live in this session (see Sources).

## Package Legitimacy Audit

Not applicable — this phase adds **zero new external package dependencies**. It extends an existing `@Component` (`WikipediaClient`) with two more `WebClient` calls against a public Wikimedia API, using libraries (`spring-webflux`, `jackson-databind`) already present and already used by this exact class.

## Architecture Patterns

### System Architecture Diagram

```
EnrichmentService.enrich()          WikiReloadService.retryWikipedia()/batchReload()
        │                                          │
        └──────────────┬───────────────────────────┘
                        │  movie.getImdbId(), originalTitle, title, year
                        ▼
              WikipediaClient.fetch(originalTitle, title, year, imdbId)
                        │
        ┌───────────────┴────────────────────────────┐
        │ imdbId present?                             │ imdbId null/blank
        ▼ yes                                          ▼
  tryFetchViaWikidata(imdbId)                    (skip straight to
        │                                         buildCandidates())
        │  1. paceRequest()
        │  2. GET wikidata.org/w/api.php
        │     ?action=query&list=search
        │     &srsearch=haswbstatement:P345={imdbId}
        │
        ├── no hits (totalhits=0) ──────────────────┐
        │                                            │
        ▼ hit found → qid = search[0].title          │
        │  3. paceRequest()                          │
        │  4. GET wikidata.org/w/rest.php/            │
        │     wikibase/v1/entities/items/{qid}/       │
        │     sitelinks/enwiki                        │
        │                                             │
        ├── HTTP 404 (no enwiki sitelink) ───────────┤
        │                                             │
        ▼ 200 → { title, url }                        │
        │  5. tryFetch(title.replace(' ','_'))        │
        │     (EXISTING method — unchanged)           │
        │     resolves sections, cleans wikitext       │
        │                                             │
        ▼ Optional<WikipediaResult>                    ▼
   (log: "found via Wikidata")          buildCandidates() → tryFetch() loop
        │                                    → tryFetchViaSearch() loop
        │                                    (EXISTING 6/10-step cascade,
        │                                     UNCHANGED)
        │                                             │
        └───────────────────┬─────────────────────────┘
                             ▼
                    WikipediaResult | WikipediaNotFoundException
                             │
                             ▼
              (dev-visibility log line appended, D-05)
```

### Recommended Project Structure
No new files/folders for production code — all changes are inside the existing `backend/src/main/java/de/moviearchive/enrichment/` package:
```
backend/src/main/java/de/moviearchive/enrichment/
├── WikipediaClient.java        # + tryFetchViaWikidata() private method, called first in fetch()
├── WikipediaResult.java        # unchanged
└── WikipediaNotFoundException.java  # unchanged

backend/src/test/resources/fixtures/
└── wikidata/                   # NEW — WireMock fixtures for the two Wikidata calls
    ├── search-found.json
    ├── search-not-found.json
    ├── sitelinks-found.json
    └── (404 case needs no fixture body — stub with .withStatus(404))
```

### Pattern 1: haswbstatement search → REST sitelinks → existing tryFetch()
**What:** Two sequential paced HTTP calls that resolve `imdbId` to a canonical Wikipedia article title, then hand off to the client's existing section-extraction machinery.
**When to use:** First thing tried inside `fetch()`, before any candidate-URL guessing, whenever `movie.getImdbId()` is non-null/non-blank.
**Example (verified live against `https://www.wikidata.org` in this session):**
```
// Step 1 — resolve IMDb ID -> Wikidata QID
// GET https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=tt1375666&format=json
// Response (VERIFIED live, 2026-08-26):
{
  "batchcomplete": "",
  "query": {
    "searchinfo": { "totalhits": 1 },
    "search": [
      { "ns": 0, "title": "Q25188", "pageid": 28584, "size": 358910, "wordcount": 578,
        "snippet": "...", "timestamp": "2026-08-21T15:53:39Z" }
    ]
  }
}
// No-match response (VERIFIED live with a bogus IMDb ID):
{ "batchcomplete": "", "query": { "searchinfo": { "totalhits": 0 }, "search": [] } }

// Step 2 — resolve QID -> enwiki sitelink
// GET https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/Q25188/sitelinks/enwiki
// Response (VERIFIED live, HTTP 200):
{ "title": "Inception", "badges": ["Q17437798"], "url": "https://en.wikipedia.org/wiki/Inception" }
// No-sitelink case (VERIFIED live against an item with no enwiki page — e.g. Q26925/dewiktionary):
// HTTP 404, empty/error body — same "swallow and fall through" handling as every other
// tryFetch* failure path in this class.

// Step 3 — feed resolved title into the EXISTING private method, unchanged:
Optional<WikipediaResult> result = tryFetch(title.replace(' ', '_'));
```

```java
// Sketch — new private method in WikipediaClient.java, following the exact
// try/catch/Optional.empty() shape of tryFetchViaSearch() in the same file.
private Optional<WikipediaResult> tryFetchViaWikidata(String imdbId) {
    if (imdbId == null || imdbId.isBlank()) return Optional.empty();
    try {
        paceRequest();
        JsonNode searchResponse = webClient.get()
                .uri("https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345={id}&format=json",
                        imdbId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        JsonNode hits = searchResponse == null ? null : searchResponse.path("query").path("search");
        if (hits == null || !hits.isArray() || hits.isEmpty()) return Optional.empty();
        String qid = hits.get(0).path("title").asText(null);
        if (qid == null || qid.isBlank()) return Optional.empty();

        paceRequest();
        JsonNode sitelink = webClient.get()
                .uri("https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/{qid}/sitelinks/enwiki", qid)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        String title = sitelink == null ? null : sitelink.path("title").asText(null);
        if (title == null || title.isBlank()) return Optional.empty();

        return tryFetch(title.replace(' ', '_'));
    } catch (WebClientResponseException e) {
        if (e.getStatusCode().value() == 404) {
            return Optional.empty(); // no Wikidata item or no enwiki sitelink — fall through
        }
        if (e.getStatusCode().value() == 429) {
            recordRateLimited(e, "wikidata imdbId=" + imdbId);
        } else {
            log.debug("Wikidata lookup failed for imdbId={} status={}", imdbId, e.getStatusCode().value());
        }
        return Optional.empty();
    } catch (Exception e) {
        log.debug("Wikidata lookup exception for imdbId={}: {}", imdbId, e.getMessage());
        return Optional.empty();
    }
}
```
**Note on `WebClient` base URL:** `this.webClient` is currently built with `.baseUrl(baseUrl)` where `baseUrl` defaults to `https://en.wikipedia.org` (test-overridden via `wikipedia.base-url`). Calling `wikidata.org` with the same client requires either (a) passing an **absolute** URI to `.uri(...)` (WebClient supports absolute URIs even on a base-URL-configured instance — they override the configured base), or (b) injecting a second `@Value("${wikidata.base-url:https://www.wikidata.org}")` and building/injecting a second `WebClient` bean. **Recommendation: option (b)** — mirrors the existing `wikipedia.base-url` pattern (already overridden via `@DynamicPropertySource` in tests) and keeps the WireMock test host swap symmetric with how `WikipediaClientTest` already overrides `wikipedia.base-url`/`tmdb.base-url`/`omdb.base-url`. A hardcoded absolute URI would NOT be swappable to a WireMock stub in tests without an ugly `System.setProperty`-style override, breaking the established `@DynamicPropertySource` test pattern.

### Anti-Patterns to Avoid
- **Wrapping the Wikidata calls (or `tryFetchViaWikidata` itself) in `@Retryable`:** Same rule CLAUDE.md and the existing `WikiReloadService` javadoc already establish for `fetch()` — the method already internally swallows failures and falls through; Spring-managed retry at this level would re-run cascades and multiply request volume during exactly the rate-limit scenario Phase 8 exists to prevent.
- **A separate, unpaced HTTP client/pacing mechanism for Wikidata:** the live rate-limit hit observed in this research session (see Summary) shows `wikidata.org` is not exempt from anonymous-tier throttling. Reuse `paceRequest()`/`backoffUntil` — a fresh, un-paced client for Wikidata would reopen the exact failure mode Phase 8 fixed for Wikipedia, just on a different host.
- **Treating a non-2xx from the sitelinks REST endpoint as a hard failure of `fetch()`:** 404 here is an expected, common outcome (most Wikidata film items DO have an enwiki sitelink, but not all — e.g. very obscure or newly-added IMDb links). It must map to "fall through to candidate cascade", never to `WikipediaNotFoundException` directly.
- **Building the SPARQL query string via manual concatenation of the IMDb ID:** not applicable if the recommended two-call approach is used, but flagged because it was a considered alternative — string-building into a SPARQL query is a real injection surface if ever revisited; IMDb IDs from TMDB are trusted/well-formed (`tt\d+`) but this is a "don't" to record regardless.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| "Which Wikidata item has IMDb ID X" | Custom fuzzy title-matching against Wikidata dumps, or a hand-rolled SPARQL query builder | `haswbstatement:P345={imdbId}` CirrusSearch keyword via the existing `action=query&list=search` idiom | This is Wikidata's own documented, purpose-built mechanism for external-ID → item resolution — exact match on a structured property, not a guess |
| "Does this Wikidata item have an English Wikipedia article, and what's it called" | Re-deriving the sitelink URL from item labels/aliases | REST API `GET /entities/items/{id}/sitelinks/enwiki` (or `wbgetentities&props=sitelinks&sitefilter=enwiki`) | Sitelinks are a first-class Wikidata data structure maintained by editors specifically to record this cross-reference — using it is strictly more reliable than any title-guessing heuristic, which is the entire premise of this phase |
| Article content extraction (plot/summary/critics) once the title is known | New parsing logic for Wikidata-resolved pages | Existing `tryFetch(pageTitle)` / `cleanWikitext()` | Zero behavioral difference between a Wikidata-resolved title and a candidate-cascade-resolved title once you have the slug — the existing method already handles redirects, section lookup, and wikitext cleanup |

**Key insight:** The entire "hard part" of this phase (resolving a name to the *correct* article, not just *a plausible* article) is Wikidata's job, not this codebase's. Everything downstream of getting a title string is unchanged — the phase is additive, not a rewrite of the extraction pipeline.

## Common Pitfalls

### Pitfall 1: `wikidata.org` has its own anonymous-tier rate limiter — independently confirmed live
**What goes wrong:** Assuming Wikidata is more lenient than `en.wikipedia.org` because it's "just metadata," and skipping pacing/backoff for the two new calls.
**Why it happens:** The existing `WikipediaClient` javadoc frames the 429/backoff machinery as specifically about `en.wikipedia.org`'s anonymous-API limiter; it's easy to assume a different host has different limits, or none.
**How to avoid:** Route both new calls through the same `paceRequest()` (and record any 429 via `recordRateLimited()`, sharing the same `backoffUntil` `AtomicReference`). **Verified this session:** an ad-hoc unpaced `curl` burst of exactly 2 requests against `www.wikidata.org` immediately returned `"You are making too many requests to the API"` — this is not a hypothetical risk.
**Warning signs:** Wikidata calls silently returning `Optional.empty()` (falling through to the candidate cascade) for movies that definitely have a P345-linked Wikidata item — the batch-reload's existing 429 misdiagnosis-as-"not found" failure mode (the exact bug Phase 8 was built to fix) recurring on the new code path.

### Pitfall 2: REST API 404 vs "genuine no Wikidata item" are different failure points that must both fall through cleanly
**What goes wrong:** Only handling "search returned zero hits" as the fall-through trigger, and letting a 404 from the *second* call (item exists, but has no `enwiki` sitelink — e.g. only a German/French Wikipedia article) propagate as an unhandled exception that aborts `fetch()` instead of proceeding to `buildCandidates()`.
**Why it happens:** It's a two-call chain with two independent "no result" outcomes (empty search array vs. HTTP 404), easy to only defensively code the first one during initial implementation.
**How to avoid:** Both must return `Optional.empty()` from `tryFetchViaWikidata()`, exactly like every other `Optional`-returning private method in this class already does. Verified live: an item that HAS a Wikidata page but no `enwiki` sitelink returns a clean HTTP 404 (tested against `Q26925/sitelinks/dewiktionary`), not a malformed/empty 200.

### Pitfall 3: `WebClient`'s configured `baseUrl` silently breaks (or silently works via absolute-URI override) depending on how the second host is wired
**What goes wrong:** `this.webClient` is built once in the constructor with `.baseUrl(baseUrl)` pointed at `en.wikipedia.org`. Passing a *relative* path like `/w/api.php?action=query...` for the Wikidata call would incorrectly resolve against `en.wikipedia.org`, not `wikidata.org` — a silent wrong-host bug, not a compile error.
**Why it happens:** Every existing call in this file uses relative paths because the client's base URL is already the right host; a developer pattern-matching off existing code could copy that relative-path style for the new Wikidata calls without noticing the host needs to change.
**How to avoid:** Either use a fully-qualified absolute URI in `.uri(...)` (WebClient honors absolute URIs even against a base-URL-configured builder) or inject a second `WebClient` bean bound to a `wikidata.base-url` property (recommended — see Pattern 1 note, keeps the existing `@DynamicPropertySource` WireMock test-override pattern symmetric across all three/four external hosts this codebase talks to).
**Warning signs:** New WireMock stubs for the Wikidata fixtures never getting hit in tests (because requests are actually going to the `en.wikipedia.org`-pointed WireMock stub path instead), or — in production — requests actually reaching the real `en.wikipedia.org` with a `/w/rest.php/wikibase/...` path that 404s in a confusingly different way than the documented Wikidata 404.

### Pitfall 4: `haswbstatement` string-property matching is case-sensitive
**What goes wrong:** Assuming CirrusSearch normalizes case for the `P345` value the way MediaWiki search normally does for free-text search terms.
**Why it happens:** [CITED: phabricator.wikimedia.org/T206613] documents that `haswbstatement` search on string-type properties (P345/IMDb ID is a string property) is case-sensitive — an uppercase or differently-cased IMDb ID would silently return zero hits.
**How to avoid:** Not a practical risk for this phase specifically — `movie.getImdbId()` is always populated from TMDB's `external_ids.imdb_id`, which TMDB always returns in the canonical lowercase `tt\d+` form matching Wikidata's own P345 value convention — but worth a defensive comment in the code, since a manually-edited or future non-TMDB-sourced `imdbId` could break silently otherwise.

## Code Examples

Verified patterns from official sources / live testing this session:

### Full resolved chain for a known film (Inception)
```
1. GET https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=tt1375666&format=json
   -> query.search[0].title = "Q25188"
2. GET https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/Q25188/sitelinks/enwiki
   -> { "title": "Inception", "url": "https://en.wikipedia.org/wiki/Inception" }
3. tryFetch("Inception")  // existing method, unchanged — resolves sections/plot/critics
```
Source: verified live against the production `wikidata.org` API in this research session, 2026-08-26.

### Dev-visibility log format (D-05)
No API to research here — purely a design choice (Claude's discretion on file path/rotation). Given the "temporary, easy to strip out, plain text, one line per attempt" requirements in CONTEXT.md, the simplest compliant shape is a plain `Files.write(path, line + "\n", APPEND, CREATE)` call from inside `WikipediaClient` (or a tiny dedicated helper), gated by nothing more than its own presence in the codebase (delete the method + call site to remove it later — no config flag needed, since D-05 explicitly frames this as throwaway). Suggested log line shapes (directly from CONTEXT.md's own examples):
```
Inception (2010): found via Wikidata
Inception (2010): fallback candidate #3 (Inception_(2010_film))
Inception (2010): not found
```
Suggested path: `backend/wiki-resolution.log` (repo-root-relative, gitignored) or a path from a new `@Value("${wiki.resolution-log.path:./wiki-resolution.log}")` — **Claude's discretion per CONTEXT.md**; exact path is a planner/executor decision, not a research finding.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Legacy `wbgetentities` (`action=` API) as the only way to read Wikidata item data | Wikibase REST API `/w/rest.php/wikibase/v1/...` reached stable v1 (General Availability) | 2024-11-11 [CITED: lists.wikimedia.org Wikibase REST API v1 announcement] | The new REST endpoint (`GET /entities/items/{id}/sitelinks/{site}`) returns a flatter, single-purpose response (`{title, url}`) than `wbgetentities`'s nested `entities.{qid}.sitelinks.{site}.title` — less `.path()` chaining, and it hands back the canonical URL directly. Both were verified working live in this session; REST is the better fit for this phase's narrow need. |
| — | `action=query&list=search&srsearch=haswbstatement:...` (CirrusSearch, `WikibaseCirrusSearch` extension) | Long-established, no recent change | This is the stable mechanism for "which item has property=value" and is unaffected by the REST API rollout — it lives entirely in the classic `action=` API family, same as the existing Wikipedia search fallback this codebase already calls. |

**Deprecated/outdated:**
- Nothing found deprecated in the relevant surface area. The new REST API is additive to, not a replacement for, the `action=` API — both remain live and were both confirmed working in this session.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Suggested dev-visibility log path (`./wiki-resolution.log`) and its exact write mechanism | Code Examples | Low — CONTEXT.md explicitly delegates this to Claude's discretion at plan/execute time; any plain-text, easily-removable file satisfies D-05 |
| A2 | Second `WebClient` bean (vs. absolute-URI override on the existing bean) is the better integration shape | Pattern 1 note | Low-medium — if the planner instead chooses the absolute-URI approach, tests must still find a way to redirect Wikidata calls to WireMock; this is flagged explicitly as a design choice with a stated reason (keeping `@DynamicPropertySource` symmetry), not a hard requirement |
| A3 | `movie.getImdbId()` is always in canonical lowercase `tt\d+` form from TMDB | Pitfall 4 | Low — based on `.claude/api-contracts.md`'s documented TMDB `external_ids.imdb_id` field and observed convention; not independently re-verified against a live TMDB response in this session (TMDB was not queried — out of this phase's research scope since imdbId population is pre-existing, unchanged code in `EnrichmentService`) |

## Open Questions

1. **Should a Wikidata item with MULTIPLE `haswbstatement:P345` hits (ambiguous P345 value, rare but possible on Wikidata) pick the first search result or bail to the fallback cascade?**
   - What we know: The search API returns a `search[]` array; `totalhits` can in theory exceed 1 (e.g. data-quality issues on Wikidata where two items were both tagged with the same, incorrect IMDb ID).
   - What's unclear: No live example of this was found/reproduced in this session — it's a theoretical edge case, not observed.
   - Recommendation: Default to `search[0]` (first/best-ranked hit) for simplicity, consistent with D-01's "unambiguous" framing referring to the *value* being unambiguous (unlike title-guessing), not requiring defensive handling of Wikidata's own rare data-quality issues. Not worth a plan task on its own — a one-line comment suffices.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Outbound HTTPS to `www.wikidata.org` | New Wikidata lookup step | ✓ (verified live from this environment) | — | Falls through to existing candidate cascade on any failure (D-01) |
| `WireMock` (test) | New WireMock fixtures for Wikidata calls | ✓ already in `build.gradle.kts` per CLAUDE.md (`3.13.0`) | 3.13.0 | — |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None — this phase's own design already treats Wikidata unavailability as a first-class fallback path to the pre-existing cascade.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + WireMock 3.13.0 + Spring Boot Test (`@SpringBootTest`, `@DynamicPropertySource`) — already used by `WikipediaClientTest` |
| Config file | `backend/src/test/java/de/moviearchive/AbstractWireMockTest.java` (base class providing the shared `wireMock` instance) |
| Quick run command | `./gradlew :backend:test --tests "de.moviearchive.movie.WikipediaClientTest"` (run from repo root, or `cd backend && ./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"`) |
| Full suite command | `./gradlew :backend:test` |

### Phase Requirements → Test Map
No requirement IDs are yet assigned to Phase 12 in REQUIREMENTS.md (`TBD` per phase description). Behavior-level test map, keyed to CONTEXT.md decisions:

| Behavior (from CONTEXT.md decision) | Test Type | Automated Command | File Exists? |
|--------------------------------------|-----------|--------------------|-------------|
| D-01: Wikidata hit (search + sitelink both succeed) short-circuits `fetch()` without touching the candidate cascade | unit/integration (WireMock) | `./gradlew :backend:test --tests "de.moviearchive.movie.WikipediaClientTest"` | ❌ Wave 0 — new test method needed |
| D-01: no imdbId → straight to existing cascade, zero Wikidata calls made | unit/integration (WireMock, assert no stub hit / verify call count) | same | ❌ Wave 0 |
| D-01: search returns zero hits → falls through to cascade | unit/integration (WireMock) | same | ❌ Wave 0 |
| D-01: item found but no `enwiki` sitelink (404) → falls through to cascade | unit/integration (WireMock, stub 404) | same | ❌ Wave 0 |
| Rate-limit (429) on either Wikidata call is paced/backed-off, not misreported as "not found" | unit/integration (WireMock, regression-test style mirroring the existing `shouldHonorRetryAfterBackoff_beforeSubsequentRequests` test) | same | ❌ Wave 0 |
| D-03: save flow / manual retry / batch-reload all exercise the new path with zero caller-side code changes | existing integration tests for `EnrichmentService`/`WikiReloadService` should continue passing unmodified once `WikipediaClient.fetch()`'s new internal step is added | `./gradlew :backend:test --tests "de.moviearchive.enrichment.*"` | ✓ existing tests, should need no edits per D-03 |
| D-05: dev-visibility log line written per attempt, one line, human-readable | unit (assert file content/format) — OR treat as manual/UAT-only given it's an explicitly throwaway dev artifact | manual verification acceptable given D-05's "temporary/dev-only, not a feature" framing | ❌ Wave 0 if automated; optional if scoped manual-only |

### Sampling Rate
- **Per task commit:** `./gradlew :backend:test --tests "de.moviearchive.movie.WikipediaClientTest"`
- **Per wave merge:** `./gradlew :backend:test`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/resources/fixtures/wikidata/search-found.json` — search response with one hit (mirror the verified live shape)
- [ ] `backend/src/test/resources/fixtures/wikidata/search-not-found.json` — empty `search: []`, `totalhits: 0`
- [ ] `backend/src/test/resources/fixtures/wikidata/sitelinks-found.json` — `{title, url, badges}` REST response shape
- [ ] New test methods in `WikipediaClientTest.java` covering: Wikidata-hit short-circuit, no-imdbId skip, zero-hits fallthrough, 404-sitelink fallthrough, 429-pacing regression (mirroring the existing `shouldHonorRetryAfterBackoff_beforeSubsequentRequests` pattern but against the Wikidata host)
- [ ] `@DynamicPropertySource` addition in `WikipediaClientTest` for whatever `wikidata.base-url`-equivalent property is chosen (see Pitfall 3 / Pattern 1 note) — required for WireMock to intercept the new calls at all
- Framework install: none — all test infrastructure (JUnit 5, WireMock, `AbstractWireMockTest`) already exists and is directly reusable

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No | No auth involved — Wikidata's public read APIs require none, same as the existing Wikipedia calls |
| V3 Session Management | No | Not applicable to this server-to-server integration |
| V4 Access Control | No | No new access-control surface — this is an internal enrichment-pipeline detail, not user-facing |
| V5 Input Validation | Yes | `imdbId` is interpolated into a URL query parameter (`srsearch=haswbstatement:P345={imdbId}`) and a REST path segment (`{qid}`). Use `WebClient`'s parameterized `.uri(template, values...)` form (as sketched in Pattern 1) — never raw string concatenation into the URI — so Spring's URI-template encoding handles escaping. `imdbId` originates from TMDB's `external_ids.imdb_id` (trusted upstream source, not user-supplied free text), and `qid` originates from Wikidata's own search response (also not user input) — low injection risk in practice, but parameterized `.uri()` calls are the correct control regardless. |
| V6 Cryptography | No | No secrets/keys involved — Wikidata's public APIs need no API key (confirmed live: unauthenticated `curl` requests succeeded) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Untrusted input reaching a raw URL/query string | Tampering | Already mitigated by design — both new URL components (`imdbId`, `qid`) originate from trusted server-side sources (TMDB response field, Wikidata's own search response), not from end-user request bodies. Still use `WebClient`'s parameterized `.uri()` form rather than string concatenation, consistent with `OmdbClient.fetch()`'s existing `.uri("/?apikey={key}&i={imdbId}&plot=full", apiKey, imdbId)` pattern in this same codebase. |
| Denial of the enrichment pipeline via a slow/unresponsive third party | Denial of Service | Already the existing pattern for all three external clients: `WebClient`'s default timeouts + the surrounding `try/catch` in `EnrichmentService`/`WikiReloadService` that logs and continues rather than blocking. The new Wikidata calls sit inside this same swallow-and-continue boundary. |

## Sources

### Primary (HIGH confidence)
- Live verified requests against `https://www.wikidata.org/w/api.php` (`action=query&list=search&srsearch=haswbstatement:P345=...`) and `https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/{id}/sitelinks/{site}` — response shapes, 404 behavior, and anonymous-tier rate limiting all directly observed in this research session, 2026-08-26.
- `backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java` — read in full this session; existing pacing/backoff/candidate-cascade/section-extraction implementation.
- `backend/src/main/java/de/moviearchive/enrichment/EnrichmentService.java`, `WikiReloadService.java`, `OmdbClient.java`, `WikipediaResult.java`, `WikipediaNotFoundException.java` — read in full this session; caller integration points and established client-pattern conventions.
- `backend/src/test/java/de/moviearchive/movie/WikipediaClientTest.java` — read in full this session; existing WireMock test pattern (including the `@DynamicPropertySource` base-URL override and the 429-backoff regression test) to extend.
- `backend/src/main/resources/application.properties` — read this session; confirms exact existing `@Value` property names (`wikipedia.base-url`, `wikipedia.request-pacing-ms`, `wiki.retry.*`) that a new `wikidata.base-url`-equivalent property should follow the same naming convention as.
- `.claude/api-contracts.md` — OMDB/Wikipedia field mapping table (confirms D-02's premise that OMDB never maps to `wiki_plot`/`wiki_summary`/`wiki_critics`).

### Secondary (MEDIUM confidence)
- [Wikidata:REST API](https://www.wikidata.org/wiki/Wikidata:REST_API) — confirms base URL `https://www.wikidata.org/w/rest.php/wikibase/v1` and that v1 is the current stable surface; exact endpoint list not detailed on this page (endpoint behavior instead confirmed by direct live testing above).
- [Help:Extension:WikibaseCirrusSearch](https://www.mediawiki.org/wiki/Help:Extension:WikibaseCirrusSearch) — `haswbstatement:P{n}={value}` syntax documentation.
- [API:Etiquette (MediaWiki)](https://www.mediawiki.org/wiki/API:Etiquette) — required `User-Agent` header format (`clientname/version (contact) framework/version`); note the existing `WikipediaClient` UA (`"MovieArchive/0.1"`, no contact info) is already not fully policy-compliant for the pre-existing `en.wikipedia.org` calls — out of this phase's stated scope to fix, but worth a planner note if `wikidata.org`'s enforcement proves stricter than what's tolerated on `en.wikipedia.org` today.
- [Wikibase REST API v1 GA announcement (lists.wikimedia.org)](https://lists.wikimedia.org/hyperkitty/list/wikidata@lists.wikimedia.org/thread/26Q4RUTPFN2SWZWOEA3TXBH5MCPHLEBU/) — v1 stability date.

### Tertiary (LOW confidence)
- [phabricator.wikimedia.org T206613](https://phabricator.wikimedia.org/T206613) — `haswbstatement` case-sensitivity on string properties (informs Pitfall 4); ticket-tracker source, not official reference documentation, but consistent with CirrusSearch's documented general behavior for string-type property statements.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies; both new endpoints tested live against production Wikidata this session
- Architecture: HIGH — read all five relevant existing source files in full; integration point (`fetch()`, before `buildCandidates()`) is explicit in CONTEXT.md D-01 and confirmed structurally sound against the read code
- Pitfalls: HIGH for rate-limiting (directly reproduced live) and 404-handling (directly reproduced live); MEDIUM for the case-sensitivity pitfall (ticket-sourced, not independently reproduced)

**Research date:** 2026-08-26
**Valid until:** 30 days (stable public API surface; Wikibase REST API v1 is GA and unlikely to break, but re-verify live response shapes if planning is delayed past ~4 weeks)
