---
status: resolved
trigger: "UAT G-10-1: Uploaded a file with ~10 movies via Bulk Import on Add Film page. UI showed 'Import started. This runs in the background.' but none of the movies were actually added to the archive. No error reported."
created: 2026-08-24T00:00:00Z
updated: 2026-08-29T00:00:00Z
---

## Current Focus

hypothesis: CONFIRMED - User's uploaded file used plain movie titles (one per line), not the required `Title;OriginalTitle;Year` semicolon-delimited format. Every line fails ImportLineParser.parse()'s "exactly 3 fields" check -> every line persists as BulkImportLineStatus.PARSE_ERROR, never SAVED. The UI has zero format guidance and zero post-202 feedback (Phase 11 scope), so the failure is completely silent.
test: Traced ImportLineParser.parse() field-count validation; confirmed no format hint/placeholder/example exists anywhere in add.vue's Bulk Import section; confirmed happy-path (correct format) works via passing integration test BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow.
expecting: N/A - root cause confirmed, goal is find_root_cause_only.
next_action: Return ROOT CAUSE FOUND diagnosis.

## Symptoms

expected: Bulk import file with ~10 titles queues async import; movies get fetched/enriched/persisted/indexed and appear in the archive.
actual: UI correctly shows "Import started. This runs in the background." (202 flow works), but after waiting, none of the 10 movies were added - archive remains empty of these titles.
errors: None reported by user - no visible error banner.
reproduction: UAT Test 1 in .planning/phases/10-bulk-import-engine/10-UAT.md - upload a file with ~10 movie titles via Bulk Import section on Add Film page.
started: Discovered during UAT session 2026-08-24, Phase 10 (Bulk Import Engine) first UAT pass.

## Eliminated

- hypothesis: Async pipeline is broken (executor misconfigured, @Async not firing, exceptions silently swallowed).
  evidence: AsyncConfig.bulkImportExecutor bean is correctly configured (core=1/max=1/queue=1, mirrors working wikiReloadExecutor pattern). BulkImportService.runImport is correctly annotated @Async("bulkImportExecutor"), called via injected bean (proxy applies). Per-line processing goes through @Lazy self-proxy (self.processLine(...)) so @Transactional actually applies - same pattern as working WikiReloadService. A CR-01 code-review fix already addresses a prior transaction-race bug (enrich() now only fires after processLine()'s transaction commits, avoiding "Movie not found for enrichment"). BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow (happy path, correctly formatted "Inception;;2010" line) is a passing integration test proving the full stack works end-to-end for well-formed input.
  timestamp: 2026-08-24

- hypothesis: TMDB key missing, causing 422 before dispatch.
  evidence: UAT report explicitly states the UI showed "Import started. This runs in the background." (i.e., a 202 was received, not a 422 error banner). Ruled out.
  timestamp: 2026-08-24

- hypothesis: MovieService.initiate()/EnrichmentService integration broken (movies matched but never persisted/indexed).
  evidence: MovieService.initiate() has straightforward idempotent check-then-insert logic, correctly called from BulkImportService.saveAndUpsert(). Covered by passing integration test that asserts movieRepository.count()==1 after a successful line import.
  timestamp: 2026-08-24

## Evidence

- timestamp: 2026-08-24
  checked: backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
  found: parse(rawLine) splits on ";" with limit -1; if fields.length != 3, returns ParsedLine with valid=false (year always null). A plain movie title with no semicolons produces a 1-element array -> immediately invalid.
  implication: Any line not in exact `Title;OriginalTitle;Year` format is marked invalid and never matched against TMDB.

- timestamp: 2026-08-24
  checked: backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java processLine()
  found: "if (!parsed.valid()) { upsertLine(user, parsed, BulkImportLineStatus.PARSE_ERROR, null); return Optional.empty(); }" - invalid lines are persisted with status=PARSE_ERROR and processing moves to the next line silently (per-line failure isolation, D-03 by design).
  implication: A file of plain movie titles (no semicolons) results in 100% of lines ending as PARSE_ERROR, and zero Movie rows are ever created - matching "none of the 10 movies were added."

- timestamp: 2026-08-24
  checked: .planning/phases/10-bulk-import-engine/10-CONTEXT.md (D-01)
  found: "File format is CSV-style: `Title;OriginalTitle;Year` per line (semicolon-delimited). `OriginalTitle` field may be empty (`Title;;Year`)." Also: "The user explicitly chose CSV-style `Title;OriginalTitle;Year` over the ROADMAP's literal 'Title (Original Title) Year' parenthetical wording."
  implication: The required format is a deliberate design decision, but it is a non-obvious, unlabeled convention that a typical user (uploading "a file with ~10 movies") would not intuit without guidance.

- timestamp: 2026-08-24
  checked: frontend/pages/add.vue (Bulk Import section, lines 212-232) and 10-02-PLAN.md/10-02-SUMMARY.md
  found: The Bulk Import section contains only a native `<input type="file" accept=".txt,.csv">` and an Import button. No label text, placeholder, example line, tooltip, or downloadable template describing the required `Title;OriginalTitle;Year` format exists anywhere in the UI. 10-02-SUMMARY.md confirms "No progress bar or per-line results list rendered here — explicitly deferred to Phase 11 per 10-CONTEXT.md D-13 boundary."
  implication: There is no way for a user to discover the required file format from the UI, and no way to see per-line outcomes (PARSE_ERROR/AMBIGUOUS/NOT_FOUND/SAVED) after upload, since Phase 11 (the results/feedback UI) has not been built yet. The only visible signal is the generic 202 acknowledgement "Import started. This runs in the background." regardless of what happens to each line afterward.

- timestamp: 2026-08-24
  checked: backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java
  found: uploadBulkImport() returns 202 immediately after dispatching bulkImportService.runImport() (fire-and-forget); no synchronous validation of file content format occurs (only a max-lines and TMDB-key check, both of which the user's upload would have passed).
  implication: A malformed-per-line-but-otherwise-valid upload always looks identical to a fully successful one from the frontend's perspective — the UAT-reported "Import started" success message with silent failure is exactly what this code path produces for an all-PARSE_ERROR batch.

- timestamp: 2026-08-24
  checked: .planning/phases/10-bulk-import-engine/10-01-SUMMARY.md coverage table
  found: Task 1's integration test only exercises a correctly-formatted single line ("Inception;;2010"); no test in BulkImportControllerTest/ImportLineParserTest/BulkImportServiceTest exercises an all-plain-titles file (the realistic user input shape) to confirm/demonstrate the all-PARSE_ERROR outcome end-to-end, nor does any test assert the frontend surfaces per-line PARSE_ERROR counts to the user.
  implication: This exact UAT scenario (unguided user upload of a plain title list) was never covered by any test in Phase 10 — consistent with it not being caught until manual UAT.

## Resolution

root_cause: The Bulk Import UI provides no guidance on the required file format (`Title;OriginalTitle;Year`, semicolon-delimited, exact 3 fields with a numeric year — D-01) and no post-upload feedback on per-line outcomes (Phase 11 scope, not yet built). The user uploaded a file of plain movie titles (one per line, no semicolons), which ImportLineParser.parse() correctly marks `valid=false` for every line (field count != 3), causing BulkImportService.processLine() to persist every line as `BulkImportLineStatus.PARSE_ERROR` and skip TMDB matching / MovieService.initiate() entirely. The backend pipeline itself is not broken — a correctly-formatted line saves and indexes correctly (proven by the passing BulkImportControllerTest#shouldSaveUniqueMatch_andPersistBulkImportLineRow integration test) — but because the 202 response and "Import started" message are unconditional and Phase 11's results UI does not yet exist, the user has no way to discover that 100% of their lines failed to parse.
fix: Format guidance and example lines added to the Bulk Import section of frontend/pages/add.vue ("One film per line, either format: Title;OriginalTitle;Year ... or Title,OriginalTitle,Year ..."), and Phase 11 (Bulk Import Feedback UI) added post-upload per-line results (PARSE_ERROR/AMBIGUOUS/NOT_FOUND/SAVED) so a failed batch is no longer silent.
verification: Confirmed by reading current frontend/pages/add.vue (format hint present) and the existence of Phase 11's results UI (11-bulk-import-feedback-ui). Resolved via subsequent phase work, not a fix applied inside this debug session.
files_changed: [frontend/pages/add.vue (Phase 11), plus Phase 11 batch-detail/results UI files]
