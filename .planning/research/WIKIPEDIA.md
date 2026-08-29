# Wikipedia Enrichment — Research Notes (v2.0)

**Researched:** 2026-08-29 (direct codebase read, no subagent — question was answerable from existing implementation)

## What we fetch today

`backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java`:

- Resolves the article via Wikidata SPARQL (IMDb ID → enwiki title), falls back to a 6-step
  candidate-URL cascade.
- Once resolved, calls `action=parse&prop=sections` to find section indexes, then
  `action=parse&prop=wikitext&section={index}` for two named sections: **Plot** and
  **Critical reception** (via `findSectionIndex` alternate-name matching).
- `cleanWikitext()` strips `<ref>` tags, `{{templates}}`, resolves `[[wikilinks]]`, removes
  section headers/HTML tags/bold-italic markers, and **collapses 3+ newlines down to exactly
  `\n\n`** — so paragraph breaks ARE preserved as double-newlines in the stored `wikiPlot` /
  `wikiCritics` strings today. This is not a backend/fetch gap.

## Why it renders as one flat block

`frontend/pages/movies/[id].vue:340,344` — both fields are interpolated into a single
`<p>{{ movie.wikipediaPlot }}</p>`. Vue/HTML collapses `\n\n` to a single space by default
(no `white-space: pre-wrap`, no per-paragraph splitting). **This is a frontend rendering bug,
not a data-availability problem** — the paragraph structure already exists in the stored text.

## What else Wikipedia's API offers

- `action=parse&prop=text` returns fully rendered **HTML** (real `<p>`, `<h2>`, `<a>` tags)
  instead of raw wikitext — would eliminate most of `cleanWikitext()`'s regex fragility
  (nested templates, wikilink edge cases) but requires HTML sanitization before rendering
  user-facing content (strip `<script>`, inline event handlers, rewrite relative links to
  absolute `en.wikipedia.org` URLs) and still needs section-scoping (`&section=N`) to avoid
  pulling the whole article.
- Additional sections available per article: Cast, Production, Release, Reception (already
  used, sometimes split into "Critical response"/"Box office"), Awards, Sequel/legacy —
  same `action=parse&prop=sections` + per-section fetch pattern as today.
- Infobox data (director, runtime, budget) is already covered by TMDB/OMDB — no need to
  duplicate via Wikipedia.
- Page thumbnail/lead image available via `action=query&prop=pageimages` — not currently used
  (TMDB poster already covers this).

## Existing but unexposed data

- `Movie.originalTitle` (backend/src/main/java/de/moviearchive/movie/Movie.java:41) and
  `Movie.wikiUrl` (line 67) **already exist as entity fields** but are not rendered on the
  detail page (`[id].vue` has no `originalTitle` or `wikiUrl`/link markup). `movie.imdbLink`
  IS already rendered (line 251-252), just not "under the title" as requested.

## External research: MediaWiki API options (WebSearch/WebFetch, 2026-08-29)

Sources:
- [API:Parsing wikitext](https://www.mediawiki.org/wiki/API:Parsing_wikitext)
- [Extension:TextExtracts](https://www.mediawiki.org/wiki/Extension:TextExtracts)

**Two distinct APIs, both already partially in play:**

1. **`action=parse&prop=text&section=N`** (parser-rendered HTML) — this is the FULL MediaWiki
   parser output for one section, well-formed HTML (`<p>`, `<a>`, `<b>`, `<i>`, internal wikilinks
   as real `<a href="/wiki/...">`). This is what a real Wikipedia page render uses — reliable,
   not the simplified/lossy extract format. Switching Plot/Critical-reception/Summary fetches
   from `prop=wikitext` (current) to `prop=text` would:
   - Eliminate our custom `cleanWikitext()` regex pipeline (template/ref/wikilink stripping) —
     the parser already resolved all of that server-side.
   - Require HTML sanitization before rendering client-side (allowlist `p/a/b/i/em/strong/ul/li`,
     rewrite relative `/wiki/...` hrefs to absolute `https://en.wikipedia.org/wiki/...`, strip any
     `<script>`/event-handler attributes) — per CLAUDE.md's XSS guidance, sanitize server-side in
     `WikipediaClient`/`EnrichmentService`, not via a raw `v-html` on unsanitized input.
   - Section-scoping (`&section=N`) still works exactly as today — no fetch-pattern change.

2. **`action=query&prop=extracts`** (TextExtracts extension, MediaWiki-Wikipedia standard,
   what "explains" a search-preview snippet) — good ONLY for the **lead/intro paragraph**
   (`exintro=1` — "content before the first section"). It does **not** support extracting an
   arbitrary named section like "Plot" — `exintro` is the only section-scoping it offers, so it
   cannot replace the Plot/Critical-reception fetch. Its own docs warn HTML mode is "not
   guaranteed well-formed" (unlike `prop=text`, which is the real parser).

**Existing but unrendered data — found during this research:** `WikipediaClient.fetch()`
already fetches section `0` (the lead/intro) via the SAME `prop=wikitext` + `cleanWikitext()`
path used for Plot/Critics, and stores it in `Movie.wikiSummary` (`wiki_summary` column) —
but `[id].vue` never reads or renders `wikipediaSummary`/`wikiSummary` at all. **The "opener"
the user wants is already being fetched and stored; it's a third pure-frontend wiring gap**,
same class of bug as `originalTitle`/`wikiUrl`.

**Recommendation:** switch `WikipediaClient`'s per-section fetch from `prop=wikitext` to
`prop=text` for Summary/Plot/Critical-reception (keeps existing section-resolution logic,
drops the regex-stripping code, needs a small server-side HTML sanitizer step), and surface
`wikiSummary` on the detail page alongside Plot/Critics — no new external endpoint needed for
the "opener" ask, it's a frontend field that already has backend data.

## Implications for requirements

- **Paragraph formatting fix** is a frontend-only change: split `wikiPlot`/`wikiCritics` on
  `\n\n` and render as multiple `<p>` tags (or `white-space: pre-wrap` on a single block) —
  no backend/API change needed for the base case.
- **Switching to `prop=text` (rendered HTML)** is a separate, larger decision (backend
  extraction rewrite + sanitization) — worth flagging as an explicit requirement choice rather
  than assuming it's needed just to fix paragraph breaks.
- **Original Title + Wikipedia link on detail page** are pure frontend wiring — the data
  already exists end-to-end (DTO exposure needs a quick check, but the entity fields exist).
