---
status: partial
phase: 06-movie-detail-personal-fields
source: [06-VERIFICATION.md]
started: 2026-05-18T15:17:00Z
updated: 2026-05-18T15:17:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Cinematic hero visual rendering
expected: Full-bleed backdrop image with dark overlay, poster thumbnail overlaid bottom-left, film title + year + tagline text readable on top
result: [pending]

### 2. OMDB fields shown when data present
expected: Rating blocks with correct labels (IMDB, TMDB, Box Office, RT/Metacritic) appear in the facts section for a film with OMDB data
result: [pending]

### 3. OMDB fields hidden when data absent
expected: No IMDB block, no box office block, no RT/Metacritic block visible for a film without OMDB enrichment
result: [pending]

### 4. Wikipedia sections conditional rendering
expected: Plot and Critical Response sections shown/hidden per v-if on wikipediaPlot and wikipediaCritics fields
result: [pending]

### 5. Personal fields auto-save (watched, rating, notes)
expected: PATCH to /api/movies/{id}/personal fired automatically after each interaction — no explicit save button required; notes debounce ~1 second
result: [pending]

### 6. Trailer lazy embed
expected: YouTube thumbnail (hqdefault.jpg) first; iframe with autoplay=1 loads only after user click; thumbnail disappears
result: [pending]

### 7. Clickable chips navigate to filtered search
expected: Actor chip → /search?actors={name}; Director chip → /search?director={name}; Genre chip → /search?genre={name}
result: [pending]

### 8. Delete modal UX and post-delete redirect
expected: Confirmation modal with "Remove from archive?" text; Cancel closes without delete; Confirm deletes and redirects to /search; film absent from results
result: [pending]

### 9. Search-to-detail NuxtLink navigation
expected: Clicking movie card poster in search results navigates to /movies/{id} detail page
result: [pending]

## Summary

total: 9
passed: 0
issues: 0
pending: 9
skipped: 0
blocked: 0

## Gaps
