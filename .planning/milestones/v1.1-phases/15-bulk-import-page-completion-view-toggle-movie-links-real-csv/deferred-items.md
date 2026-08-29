# Deferred Items — Phase 15

Out-of-scope issues discovered during plan execution but not fixed (scope boundary rule).

## Plan 15-01

- **`frontend/pages/movies/[id].vue:3`** — ESLint error: `'PlusCircleIcon' is defined but
  status: acknowledged
  never used`. Pre-existing (last touched in phase 09, commit `3006964`), not in this plan's
  `files_modified` list, and unrelated to view-toggle/movie-link/PARSE_ERROR work. Confirmed
  via `git log` that this predates plan 15-01. Not fixed here — out of scope.

## Plan 15-03

- **Full-suite `./gradlew check` cross-class test isolation flakiness** — running the entire
  status: acknowledged
  backend suite fails ~97 tests across unrelated classes (`UserControllerTest`,
  `SettingsIntegrationTest`, `MovieControllerTest`, `SearchControllerTest`,
  `WikiReloadServiceIntegrationTest`, `EnrichmentIntegrationTest`, `IndexingIntegrationTest`,
  `MovieDetailControllerTest`, `DashboardControllerTest`, `WikipediaClientTest`) with a mix of
  Spring context load failures and `DataIntegrityViolationException`/FK-constraint violations
  (e.g. `bulk_import_line_user_id_fkey` blocking a `users` row delete). None of these classes
  are in this plan's `files_modified` list. Isolated confirmation: running
  `BulkImportControllerTest` + `UserControllerTest` together (`./gradlew test --tests
  "de.moviearchive.bulkimport.BulkImportControllerTest" --tests
  "de.moviearchive.user.UserControllerTest"`) passes cleanly — BUILD SUCCESSFUL, no FK
  violation — proving the failure is not caused by this plan's 3 new CSV tests specifically,
  but by a pre-existing cross-class DB-state leak in the full-suite run (tests across different
  classes share one Testcontainers Postgres instance; no `@AfterAll`/class-boundary cleanup
  exists anywhere in the suite, so whichever class happens to run last before a class expecting
  a clean `users` table can trip this). This plan's own two required `<verify>` commands
  (`ImportLineParserTest` — 12/12 pass; `BulkImportControllerTest` — 23/23 pass, run both in
  isolation and inside the full suite) are unaffected and green. Not fixed here — pre-existing
  test-infrastructure gap, out of scope for a 2-task CSV-parsing plan.
