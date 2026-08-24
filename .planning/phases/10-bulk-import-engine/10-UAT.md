---
status: diagnosed
phase: 10-bulk-import-engine
source: [10-VERIFICATION.md]
started: 2026-08-24T15:40:00Z
updated: 2026-08-24T15:50:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Visual rendering of the Bulk Import section on the Add Film page
expected: Section renders below the poster grid with an hr separator, matches settings.vue's heading style (no rounded corners anywhere). Button disables until a file is chosen and shows "Uploading..." while in flight. FormErrorBanner (on error) and inline success message (on 202) appear/clear correctly.
result: issue
reported: "Im Prinzip schon, den letzten Punkt verstehe ich nicht so ganz. Wenn ich eine Datei ausgewählt habe und dann auf Import geklickt habe, wird Import ausgegraut. Und es steht darunter 'Import started. This runs in the background.' Allerdings: Ich bezweifle ehrlich gesagt, dass das Ganze funktioniert. Ich habe jetzt eine Datei mit ungefähr zehn Filmen hochgeladen und importiert und keiner von den Filmen ist irgendwie geadded worden."
severity: major

## Summary

total: 1
passed: 0
issues: 1
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
  root_cause: "The Bulk Import UI gives no format guidance and no post-upload results feedback. The user uploaded plain movie titles (one per line), but the backend requires strict `Title;OriginalTitle;Year` semicolon-delimited lines (design decision D-01 in 10-CONTEXT.md). ImportLineParser.parse() correctly marks every line valid=false because split(\";\", -1) on a title with no semicolons yields a 1-element array, not 3 fields. BulkImportService.processLine() then persists every such line as PARSE_ERROR and never calls TMDB matching or MovieService.initiate() (working as designed per D-03 per-line failure isolation). The controller returns 202 and the frontend shows \"Import started. This runs in the background.\" unconditionally — there is no code path surfacing per-line PARSE_ERROR back to the user, so a 100%-failure batch is indistinguishable from a 100%-success batch in the UI. Per-line results display is explicitly deferred to Phase 11 (confirmed in 10-02-SUMMARY.md), so today there is zero mechanism for the user to learn a line failed to parse. The happy-path pipeline itself is proven correct (BulkImportControllerTest passes for a correctly-formatted line) — this rules out async/executor/transaction bugs."
  artifacts:
    - path: "backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java"
      issue: "Correctly implements the strict 3-field format per spec, but this format is never communicated to end users."
    - path: "frontend/pages/add.vue"
      issue: "Bulk Import section (~lines 212-232) has no format guidance/example/placeholder text near the file input."
    - path: "backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java"
      issue: "processLine() persists PARSE_ERROR rows silently with no mechanism in this phase for the frontend to query/display them."
  missing:
    - "Visible format hint/example in the Bulk Import section of add.vue (e.g. \"One film per line: Title;OriginalTitle;Year, e.g. Inception;;2010\")"
    - "Minimal post-import feedback so a 0-saved batch is never indistinguishable from a success — e.g. detect \"0 lines saved\" / all-PARSE_ERROR and surface a warning, without building the full Phase 11 per-line results UI"
  debug_session: ".planning/debug/bulk-import-not-adding-movies.md"
