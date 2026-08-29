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

## Implications for requirements

- **Paragraph formatting fix** is a frontend-only change: split `wikiPlot`/`wikiCritics` on
  `\n\n` and render as multiple `<p>` tags (or `white-space: pre-wrap` on a single block) —
  no backend/API change needed for the base case.
- **Switching to `prop=text` (rendered HTML)** is a separate, larger decision (backend
  extraction rewrite + sanitization) — worth flagging as an explicit requirement choice rather
  than assuming it's needed just to fix paragraph breaks.
- **Original Title + Wikipedia link on detail page** are pure frontend wiring — the data
  already exists end-to-end (DTO exposure needs a quick check, but the entity fields exist).
