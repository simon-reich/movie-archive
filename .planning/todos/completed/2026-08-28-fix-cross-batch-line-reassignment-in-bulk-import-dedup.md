---
created: 2026-08-28T16:58:31.357Z
title: Fix cross-batch line reassignment in bulk import dedup
area: bulk-import
severity: major
files:
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportService.java:337-367 (findExistingRow, upsertLine)
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportLineRepository.java
  - backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java
---

## Problem

`BulkImportService.findExistingRow()` looks up an existing `BulkImportLine` row to reuse on re-upload, scoped only by `(userId, normalized title, year)` — never by `batchId`. `upsertLine()` then unconditionally calls `row.setBatch(batch)` (line 347) on whatever row it finds.

Consequence: if a user uploads a second, unrelated batch containing a title+year that already exists in an *older* batch (in any status — AMBIGUOUS, NOT_FOUND, PARSE_ERROR, or even SAVED), that row silently gets reassigned from the old batch to the new one. The old batch's `totalLines` count and status-count accounting on the batch-detail page then no longer matches its actual persisted rows — a line the user expects to still be in Batch A has quietly moved to Batch B.

Pre-existing since Phase 10 (introduced in commit `eff92a5`, the original tracer plan for bulk import). Not introduced by, and does not block, Phase 15 (view toggle / movie links / inline resolve / CSV parsing) — none of Phase 15's plans touch `findExistingRow()` or `upsertLine()`'s reuse logic.

Found by: code review during Phase 15 execution (`15-REVIEW.md`, finding CR-01) and confirmed independently by phase verification (`15-VERIFICATION.md`). The existing test suite works around this exact collision by calling `bulkImportLineRepository.deleteAll()` between two batches in `BulkImportControllerTest.shouldReturn404_whenResolvingLineFromDifferentBatch` rather than exercising the collision as intended behavior — evidence this was never a deliberate design choice.

## Solution

Scope `findExistingRow()`'s lookup by `batchId` in addition to `userId` + normalized title/year, so each batch stays a fully independent snapshot of that one import run — no cross-batch row reuse or reassignment. Practically:

- Add batch-scoped query methods to `BulkImportLineRepository` (e.g. `findByUserIdAndBatchIdAndNormalizedTitleAndYear`, and the batch-scoped equivalents of the other two lookup variants in `findExistingRow()`).
- Update `findExistingRow()` to take the current `batchId` and use the batch-scoped queries.
- Add a regression test: upload a title+year in Batch A (leave it AMBIGUOUS/NOT_FOUND), then upload the same title+year in Batch B — assert Batch A's line count/row is unchanged and Batch B gets its own independent row.
- Sanity-check the "same file re-uploaded into the same batch" retry path still dedups correctly (that's the one legitimate reuse case `findExistingRow()` exists for).
