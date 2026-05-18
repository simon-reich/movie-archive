# Phase 6: Movie Detail & Personal Fields - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-18
**Phase:** 06-movie-detail-personal-fields
**Areas discussed:** Detail page layout, Personal fields UX, OMDB absent behavior, Trailer embed approach, Delete film

---

## Detail Page Layout

| Option | Description | Selected |
|--------|-------------|----------|
| Backdrop + poster overlay | Full-width backdrop as hero background, poster overlaid left, title/year/tagline right | ✓ |
| Poster sidebar layout | Fixed left sidebar with poster, metadata in right column | |
| Poster-only header | Large poster centered at top, metadata below | |

**User's choice:** Backdrop + poster overlay (cinematic layout)

---

| Option | Description | Selected |
|--------|-------------|----------|
| Metadata → Personal → Wikipedia → Trailer | Standard top-down priority order | |
| Metadata → Trailer → Personal → Wikipedia | Trailer early for visual engagement | |
| Two-column: main (facts + Wikipedia) / sidebar (personal + trailer) | Left column facts+wiki, right sidebar personal+trailer, cast/crew at bottom full-width credits style | ✓ |

**User's choice (free text):** Primary facts (year, runtime, genres, director, writer, main cast, language, country, ratings, synopsis) in left column, then Wikipedia content. Personal fields + trailer in right sidebar. Full cast & crew at the very bottom in credits-style multi-column layout.

---

| Option | Description | Selected |
|--------|-------------|----------|
| ~1/3 right sidebar | Main column 2/3, sidebar 1/3 | |
| ~1/4 right sidebar | Narrow sidebar | |
| Claude decides the column ratio | Whatever fits 16:9 trailer + personal fields | ✓ |

**User's choice:** Claude decides

---

## Personal Fields UX

| Option | Description | Selected |
|--------|-------------|----------|
| Star rating (1–10 stars) | 10 clickable stars, immediately visual | ✓ |
| Number input (0–10, step 0.5) | Precise numeric input | |
| Slider (0–10) | Horizontal drag | |

**User's choice:** Star rating 1–10

---

| Option | Description | Selected |
|--------|-------------|----------|
| Auto-save on each change | Watched/rating saves immediately, notes debounced | ✓ |
| Explicit save button | All fields saved at once | |
| Hybrid: auto watched+rating, explicit notes | Toggle/stars immediate, notes require save | |

**User's choice:** Auto-save on each change

---

## OMDB Absent Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Hide OMDB fields individually | Fields simply don't appear when null | ✓ |
| Show OMDB section with notice | "Data not available" placeholder | |
| Collapsible OMDB section | Hidden if all null | |

**User's choice:** Hide individually — clean, no placeholder messages

---

## Trailer Embed Approach

| Option | Description | Selected |
|--------|-------------|----------|
| Poster thumbnail → click to load iframe | YouTube thumbnail + play button, iframe on click | ✓ |
| Inline iframe, loads immediately | YouTube iframe embedded directly on page load | |

**User's choice:** Poster thumbnail with click-to-load

---

## Delete Film

| Option | Description | Selected |
|--------|-------------|----------|
| Danger zone at bottom | Delete button at bottom of page | |
| Hero area | Trash icon near top | |
| Claude decides placement | Best fit for editorial layout | ✓ |

**User's choice:** Claude decides placement. User described: clicking Delete opens a confirmation modal before proceeding. After deletion: remove from both OpenSearch and Postgres, redirect to /search.

---

## Claude's Discretion

- Column width ratio for two-column layout
- Delete button placement within the page
- Full cast & crew column count and grouping
- Star rating component (lucide icons or custom SVG)
- Notes debounce duration
- Poster/backdrop fallback when image unavailable

## Deferred Ideas

- Average personal rating on dashboard (post-Phase 6)
- Watched/unwatched dashboard stats (post-Phase 6)
- Mobile polish for detail page (Phase 7)
