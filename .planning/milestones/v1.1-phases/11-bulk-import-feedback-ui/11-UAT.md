---
status: complete
phase: 11-bulk-import-feedback-ui
source: [11-01-SUMMARY.md, 11-02-SUMMARY.md, 11-03-SUMMARY.md, 11-04-SUMMARY.md, 11-05-SUMMARY.md]
started: 2026-08-25T06:01:33Z
updated: 2026-08-25T06:10:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Kill any running server/service. Clear ephemeral state (temp DBs, caches, lock files). Start the application from scratch. Server boots without errors, the new V10 migration (bulk_import_batch table + batch_id/poster_path columns) completes, and a primary query (health check, homepage load, or basic API call) returns live data.
result: pass

### 2. Every bulk-import upload creates a durable batch record
expected: Every bulk-import upload creates a durable batch record (bulk_import_batch) capturing total_lines, and every persisted bulk_import_line row is tagged with its batch_id
result: pass
source: automated
coverage_id: D1

### 3. SAVED line's poster_path captured at save time
expected: A SAVED bulk-import line's poster_path is captured at save time from the already-fetched TMDB match, with zero additional TMDB calls
result: pass
source: automated
coverage_id: D2

### 4. Upload response includes batchId
expected: POST /movies/bulk-import's 202 response includes a non-blank batchId matching the persisted lines' batch_id
result: pass
source: automated
coverage_id: D3

### 5. Live progress events without polling
expected: While an import is running, a client connected to the progress endpoint receives increasing processed/total events without polling
result: pass
source: automated
coverage_id: D1

### 6. Late-connecting client learns import already finished
expected: A client connecting after the import already finished immediately learns it is complete instead of hanging open forever
result: pass
source: automated
coverage_id: D2

### 7. Progress stream is owner-only (IDOR protection)
expected: A user cannot read another user's import progress stream (IDOR protection on the batchId path variable)
result: pass
source: automated
coverage_id: D3

### 8. Batch list endpoint returns batches newest-first with status counts
expected: GET /movies/bulk-import/batches returns the authenticated user's batches newest-first, each with per-status counts summing to the batch's persisted line count
result: pass
source: automated
coverage_id: D1

### 9. Batch detail endpoint returns per-line data
expected: GET /movies/bulk-import/batches/{batchId} returns per-line title/originalTitle/year/status/posterPath for the owner, with posterPath null (not fabricated) for AMBIGUOUS/NOT_FOUND lines
result: pass
source: automated
coverage_id: D2

### 10. Batch detail endpoint is owner-only
expected: GET /movies/bulk-import/batches/{batchId} returns 403 for a batch owned by a different user and 404 for an unknown batchId (IDOR protection)
result: pass
source: automated
coverage_id: D3

### 11. Live processed/total updates on progress page
expected: While an import is running, the user sees processed/total update live on /imports/{batchId} without a manual refresh
result: pass
source: automated
coverage_id: D1

### 12. Results list shown once import completes
expected: Once the import completes, the same page shows the per-line results list (title, poster or fallback, status)
result: pass
source: automated
coverage_id: D2

### 13. Already-completed batch shows results immediately
expected: A user who navigates to /imports/{batchId} for an already-completed batch sees the results immediately, not a stuck 'connecting' state
result: pass
source: automated
coverage_id: D3

### 14. Reach the new batch's progress page after upload
expected: After a successful bulk-import upload on the Add Film page, the user can reach the new batch's progress page (a "Track progress ->" link appears and navigates to /imports/{batchId})
result: pass

### 15. Batch list page shows past import history
expected: The user can browse a list of past bulk-import batches (date, line count, status distribution) at any time, not just right after uploading
result: pass
source: automated
coverage_id: D1

### 16. Clicking a batch opens its full per-line results
expected: Clicking a batch row in the list opens its full per-line results (persisted-report requirement)
result: pass

### 17. Batch list reachable from main navigation
expected: The batch list page is reachable from the app's main navigation (desktop and mobile), not only via a direct URL
result: pass

## Summary

total: 17
passed: 17
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
