---
status: complete
phase: 10-bulk-import-engine
source: [10-01-SUMMARY.md, 10-02-SUMMARY.md, 10-03-SUMMARY.md]
started: 2026-08-24T14:26:59Z
updated: 2026-08-24T14:35:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Multipart upload endpoint returns 202 Accepted synchronously, dispatches async processing
expected: Multipart upload endpoint returns 202 Accepted synchronously, dispatches async processing
result: pass
source: automated
coverage_id: D1

### 2. Line parsing (D-01/D-02/D-03): blank-skip, field-count/year/title validation, trailing-empty-field preservation
expected: Line parsing (D-01/D-02/D-03): blank-skip, field-count/year/title validation, trailing-empty-field preservation
result: pass
source: automated
coverage_id: D2

### 3. Exact-year TMDB matching with original-title narrowing on ambiguity (D-04/D-05/D-06/D-07)
expected: Exact-year TMDB matching with original-title narrowing on ambiguity (D-04/D-05/D-06/D-07)
result: pass
source: automated
coverage_id: D3

### 4. Unique match reuses MovieService.initiate() + EnrichmentService.enrich() exactly as /movies/save (D-12)
expected: Unique match reuses MovieService.initiate() + EnrichmentService.enrich() exactly as /movies/save (D-12)
result: pass
source: automated
coverage_id: D4

### 5. Re-upload skips already-SAVED lines with zero additional TMDB calls; non-SAVED lines retried; 422 fail-fast with zero TMDB calls; 503 on third overlapping trigger
expected: Re-upload skips already-SAVED lines with zero additional TMDB calls; non-SAVED lines retried; 422 fail-fast with zero TMDB calls; 503 on third overlapping trigger
result: pass
source: automated
coverage_id: D5

### 6. uploadBulkImport composable posts multipart FormData to /api/movies/bulk-import with Authorization header
expected: uploadBulkImport composable function posts multipart FormData to /api/movies/bulk-import with Authorization header, no Content-Type override
result: pass
source: automated
coverage_id: D1

### 7. Visual rendering of the Bulk Import section on the Add Film page
expected: Section renders below the poster grid with an hr separator, matches settings.vue's heading style (no rounded corners anywhere). Button disables until a file is chosen and shows "Uploading..." while in flight. FormErrorBanner (on error) and inline success message (on 202) appear/clear correctly.
result: pass

### 8. All-lines-unparseable batches are rejected synchronously with 400 and a specific message
expected: Uploading a file where no lines match Title;OriginalTitle;Year is rejected synchronously with 400 and a specific message; no bulk_import_line row created, no TMDB call fires
result: pass
source: automated
coverage_id: D1

### 9. Partial-failure batches (some parseable lines, some not) are unaffected
expected: Partial-failure batches (some parseable lines, some not) are unaffected — still processed per-line and still return 202
result: pass
source: automated
coverage_id: D2

### 10. A 400 rejection with a message body surfaces the exact backend message
expected: A 400 rejection with a message body sets bulkImportError to that exact backend message instead of the generic fallback
result: pass
source: automated
coverage_id: D4

### 11. Bulk Import format hint is always visible before upload
expected: The Bulk Import section on the Add Film page always shows a visible "Title;OriginalTitle;Year" format hint (e.g. "One film per line: Title;OriginalTitle;Year — leave Original Title empty if unknown, e.g. \"Inception;;2010\".") near the file input, before any upload — and uploading a file where every line fails to parse (e.g. plain movie titles with no semicolons) now shows a specific error message instead of silently claiming success.
result: pass

## Summary

total: 11
passed: 11
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-10-1
  truth: "Section renders below the poster grid with an hr separator, matches settings.vue's heading style (no rounded corners anywhere). Button disables until a file is chosen and shows \"Uploading...\" while in flight. FormErrorBanner (on error) and inline success message (on 202) appear/clear correctly."
  status: resolved
  resolved_by: 10-03-PLAN.md
  resolved_at: 2026-08-24
  reason: "User reported: uploaded a file with ~10 movies, UI shows 'Import started. This runs in the background.' but none of the movies were actually added."
  severity: major
  test: 1
  root_cause: "The Bulk Import UI gave no format guidance and no post-upload results feedback. The user uploaded plain movie titles (one per line), but the backend requires strict `Title;OriginalTitle;Year` semicolon-delimited lines (design decision D-01 in 10-CONTEXT.md). ImportLineParser.parse() correctly marks every line valid=false because split(\";\", -1) on a title with no semicolons yields a 1-element array, not 3 fields. BulkImportService.processLine() then persisted every such line as PARSE_ERROR and never called TMDB matching or MovieService.initiate() (working as designed per D-03 per-line failure isolation). The controller returned 202 and the frontend showed \"Import started. This runs in the background.\" unconditionally — there was no code path surfacing per-line PARSE_ERROR back to the user, so a 100%-failure batch was indistinguishable from a 100%-success batch in the UI."
  artifacts:
    - path: "backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java"
      issue: "Correctly implements the strict 3-field format per spec, but this format was never communicated to end users."
    - path: "frontend/pages/add.vue"
      issue: "Bulk Import section had no format guidance/example/placeholder text near the file input. Fixed in 10-03."
    - path: "backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java"
      issue: "processLine() persists PARSE_ERROR rows silently — this per-line behavior is unchanged by design (D-03); the fix adds a whole-batch pre-flight gate in BulkImportController instead."
  missing: []
  debug_session: ".planning/debug/bulk-import-not-adding-movies.md"
