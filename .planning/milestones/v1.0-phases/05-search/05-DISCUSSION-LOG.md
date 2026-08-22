# Phase 5: Search - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-17
**Phase:** 05-search
**Areas discussed:** Home page / entry point, Search behavior, Result + filter layout, Clickable navigation (SRCH-04)

---

## Home page / entry point

### Q1: Where does the search UI live?

| Option | Description | Selected |
|--------|-------------|----------|
| / is the search page | index.vue becomes the full search experience. Cleanest navigation. | |
| /search is the search page, / is a dashboard | index.vue shows summary/welcome. /search is dedicated search route. | ✓ |
| / redirects to /search | index.vue just redirects. Search lives at /search. | |

**User's choice:** `/search` is the search page; `/` is a dashboard.

---

### Q2: What does the dashboard show?

| Option | Description | Selected |
|--------|-------------|----------|
| Recently added films (last N posters) | Quick visual summary. Minimal backend work. | |
| Archive stats + recent films | Film count, stats, genre breakdown, recent additions. | ✓ |
| Just a search bar navigating to /search | Minimal — mostly empty. | |

**User's choice:** Archive stats + recent films.

---

### Q3: Which stats on the dashboard?

**Selected:** Total films in archive, Top genres
**Free-text additions:** Language breakdown, IMDB rating breakdown (10 to 1 histogram), movie recommendation of the day as a poster

**Notes:** User explicitly added language breakdown, IMDB rating histogram, and a "movie of the day" feature beyond the offered options.

---

### Q4: "Movie recommendation of the day" clarification

| Option | Description | Selected |
|--------|-------------|----------|
| Random unwatched film (date-seeded) | Pick one random unwatched film daily. Simple, no ML needed. | ✓ |
| Random film regardless of watched status | Purely random from full archive. | |
| You decide the algorithm | Claude picks the most sensible approach. | |

**User's choice:** Random unwatched film, date-seeded.

---

## Search behavior

### Q1: How does search trigger?

| Option | Description | Selected |
|--------|-------------|----------|
| Live as you type (debounced ~300ms) | Results update automatically. | ✓ |
| Explicit submit (button or Enter) | Consistent with /add page. | |

**User's choice:** Live as you type, ~300ms debounce.

---

### Q2: What does /search show on page load?

| Option | Description | Selected |
|--------|-------------|----------|
| All films (match_all query) | Full archive sorted by default. | ✓ (with modification) |
| Empty prompt | Blank area with prompt text. | |

**User's choice:** All films, but sorted by title A–Z.
**Notes:** User answered in German: "all films aber sortiert nach titel bitte" = all films but sorted by title please.

---

### Q3: Which fields does free-text search?

| Option | Description | Selected |
|--------|-------------|----------|
| All indexed text fields | Full power of custom analyzer. | ✓ |
| Core fields only (title, overview, director, actors) | Narrower, fewer surprises. | |
| You decide | Claude picks field set with boosts. | |

**User's choice:** All indexed text fields.

---

## Result + filter layout

### Q1: How are search results displayed?

| Option | Description | Selected |
|--------|-------------|----------|
| Poster grid (like /add page) | Visual, poster-first. Less metadata. | |
| Metadata-rich list / cards | Poster thumbnail + metadata. More scannable. | |
| Switchable grid/list | User toggles between views. Most work. | ✓ (with modification) |

**User's choice:** Switchable grid/list. Free-text addition: "runtime" must also appear in the rich list view.
**Notes:** "switchable aber bitte noch mit runtime in der rich list" = switchable but please also with runtime in the rich list.

---

### Q2: Where do filters + sort live?

| Option | Description | Selected |
|--------|-------------|----------|
| Collapsible filter panel above results | Hidden by default, expanded via button. | ✓ |
| Always-visible sidebar | Permanently visible beside results. | |
| Filter drawer / slide-over | Overlay panel, mobile-friendly. | |

**User's choice:** Collapsible panel above results.

---

### Q3: What filters and what UI controls?

**Selected:** "You decide per field type"

**Free-text additions:**
- Also include actors/cast filter
- Runtime cutoff input (maximum length only, not minimum)
- "Not yet watched" toggle (boolean — only show unwatched films)
- No separate "watched" filter needed (only "not yet watched")
- Languages filter
- Production country filter

**Notes:** Full German response: "1. aber bitte auch schauspielerInnen, runtime cutoff input (nur wichtig maximale länge), toggle nur für not yet watched, filtern nach watched braucht es nicht, dann bitte noch sprachen und production country nicht vergessen"

Final filter set: genre, director, actors, year (range), IMDB rating (range), content rating, runtime (max), not-yet-watched toggle, language, production country.

---

## Clickable navigation (SRCH-04)

### Q1: How does filter get applied when clicking an attribute?

| Option | Description | Selected |
|--------|-------------|----------|
| URL query params (/search?director=Nolan) | Shareable, bookmarkable, back-button works. | ✓ |
| Pinia state (transient) | Simpler but refresh loses filter. | |
| You decide | Claude picks based on existing patterns. | |

**User's choice:** URL query params.

---

### Q2: How are multiple values for the same filter combined?

| Option | Description | Selected |
|--------|-------------|----------|
| AND within same filter, AND across filters | Strictest — results narrow with each addition. | |
| OR within same filter, AND across filters | More results per group. Common for faceted search. | ✓ |

**User's choice:** OR within same filter, AND across filter groups.

---

### Q3: Where do clickable attributes appear?

| Option | Description | Selected |
|--------|-------------|----------|
| On search result cards/list items | Available in Phase 5 immediately. | ✓ |
| On the movie detail page (Phase 6) | DETAIL-05 scope. | ✓ |

**User's choice:** Both — Phase 5 adds clickable attributes to search result cards; Phase 6 adds them to the detail page.

---

## Claude's Discretion

- OpenSearch query structure (multi_match vs. bool/should with per-field boosts)
- Debounce implementation approach
- How autocomplete suggestions for director/actors are fetched
- Dashboard aggregation query structure
- Date-seeding algorithm for "movie of the day"
- Pagination strategy (infinite scroll, load-more, or standard pagination)
- Default view mode if no stored preference
- Filter panel expand/collapse animation
- Exact URL query param naming

## Deferred Ideas

- Clickable attributes on detail page — DETAIL-05 / Phase 6
- Average personal rating on dashboard — depends on Phase 6 personal fields
- Watched vs. unwatched count on dashboard — depends on Phase 6
- Infinite scroll complexity — if too complex, fall back to load-more
