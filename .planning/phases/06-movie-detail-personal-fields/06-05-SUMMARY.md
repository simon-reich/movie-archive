---
phase: "06"
plan: "05"
subsystem: frontend-components
tags: [vue, components, star-rating, trailer-embed, nuxtlink, navigation]
dependency_graph:
  requires:
    - "06-03"  # movie detail page with personal fields (MovieCard/MovieListItem already existed)
  provides:
    - StarRating.vue (10-star interactive widget with null/deselect)
    - TrailerEmbed.vue (lazy YouTube embed with thumbnail + iframe swap)
    - MovieCard.vue detail navigation via NuxtLink
    - MovieListItem.vue detail navigation via NuxtLink
  affects:
    - frontend/pages/movies/[id].vue (will consume StarRating and TrailerEmbed)
    - frontend/pages/search.vue (MovieCard and MovieListItem now navigate to detail)
tech_stack:
  added: []
  patterns:
    - lucide-vue-next icons (StarIcon, PlayIcon) for interactive UI elements
    - lazy embed pattern (thumbnail first, iframe only on user click)
    - NuxtLink for SPA navigation from search results to detail page
key_files:
  created:
    - frontend/components/StarRating.vue
    - frontend/components/TrailerEmbed.vue
    - frontend/test/unit/components/TrailerEmbed.spec.ts
  modified:
    - frontend/components/MovieCard.vue
    - frontend/components/MovieListItem.vue
decisions:
  - "Wrap only poster img in MovieCard with NuxtLink (not entire card) to avoid intercepting genre/director chip clicks"
  - "TrailerEmbed lazy-loads iframe on user click — YouTube tracking not activated until explicit user action (D-11)"
  - "StarRating deselect: clicking already-selected star emits null (unrated is a valid state per D-06)"
metrics:
  duration: "~15 min"
  completed: "2026-05-18"
  tasks_completed: 2
  files_changed: 5
---

# Phase 6 Plan 05: Frontend Components (StarRating, TrailerEmbed, Navigation) Summary

**One-liner:** 10-star rating widget with deselect, lazy YouTube trailer embed with thumbnail-to-iframe swap, and NuxtLink detail page activation on MovieCard/MovieListItem.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | StarRating.vue + TrailerEmbed.vue + 5 Vitest tests | 95f2e75 | StarRating.vue, TrailerEmbed.vue, TrailerEmbed.spec.ts |
| 2 | NuxtLink activation in MovieCard and MovieListItem | 3a236dc | MovieCard.vue, MovieListItem.vue |

## What Was Built

### StarRating.vue

Interactive 10-star rating widget:
- Renders exactly 10 star buttons using `lucide-vue-next` `StarIcon`
- Filled stars (1 to `modelValue`) shown in `text-primary fill-primary`
- Empty stars shown in `text-muted-foreground`
- Clicking star N where `modelValue != N` emits `update:modelValue` with N
- Clicking star N where `modelValue == N` emits `update:modelValue` with null (deselect)
- `role="group"` with per-star `aria-label` for accessibility
- No rounded corners per UI-SPEC

### TrailerEmbed.vue

Lazy YouTube embed component:
- When `trailerKey` prop is provided: shows YouTube thumbnail with terracotta play overlay square
- When `trailerKey` is null/undefined: renders nothing (`v-if` on root)
- On thumbnail click: `trailerActive = true` replaces thumbnail with iframe
- Thumbnail URL: `https://img.youtube.com/vi/{key}/hqdefault.jpg`
- Embed URL: `https://www.youtube.com/embed/{key}?autoplay=1`
- No YouTube request before user clicks (privacy-preserving per D-11 and T-06-05-01)

### TrailerEmbed.spec.ts (5 tests — all green)

1. Renders YouTube thumbnail when `trailerKey` is provided
2. Does not render when `trailerKey` is null
3. Shows play button overlay before click (no iframe)
4. Replaces thumbnail with iframe after click
5. Iframe src uses correct YouTube embed URL with autoplay

### MovieCard.vue (NuxtLink activation)

Wrapped the poster `<img>` element with `<NuxtLink :to="\`/movies/${movie.id}\`">`. Genre and director chip `<button>` elements remain as siblings — independently clickable. One NuxtLink in the component (poster area only).

### MovieListItem.vue (NuxtLink activation)

Wrapped the title text with `<NuxtLink :to="\`/movies/${movie.id}\`">`. Added `hover:text-primary` for visual feedback. Genre and director chips remain as siblings.

## Verification

- 5 TrailerEmbed Vitest tests: PASSED
- Full frontend test suite: 118 tests passed, 1 file skipped (movies-id todos), 0 failures

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — components are fully implemented. StarRating and TrailerEmbed are ready to be used in the movie detail page (`frontend/pages/movies/[id].vue`).

## Threat Flags

None — no new network endpoints or auth paths introduced. TrailerEmbed follows the lazy embed pattern specified in T-06-05-01 (YouTube tracking not activated until user click).

## Self-Check: PASSED

- `frontend/components/StarRating.vue` — FOUND
- `frontend/components/TrailerEmbed.vue` — FOUND
- `frontend/test/unit/components/TrailerEmbed.spec.ts` — FOUND
- `frontend/components/MovieCard.vue` — modified, FOUND
- `frontend/components/MovieListItem.vue` — modified, FOUND
- Commit 95f2e75 — FOUND
- Commit 3a236dc — FOUND
