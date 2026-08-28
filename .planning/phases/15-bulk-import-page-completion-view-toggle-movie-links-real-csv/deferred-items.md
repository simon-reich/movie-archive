# Deferred Items — Phase 15

Out-of-scope issues discovered during plan execution but not fixed (scope boundary rule).

## Plan 15-01

- **`frontend/pages/movies/[id].vue:3`** — ESLint error: `'PlusCircleIcon' is defined but
  never used`. Pre-existing (last touched in phase 09, commit `3006964`), not in this plan's
  `files_modified` list, and unrelated to view-toggle/movie-link/PARSE_ERROR work. Confirmed
  via `git log` that this predates plan 15-01. Not fixed here — out of scope.
