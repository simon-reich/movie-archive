---
created: 2026-08-24T14:16:32.372Z
title: Support real CSV parsing for bulk import (and matching CSV export)
area: bulk-import
severity: minor
files:
  - backend/src/main/java/de/moviearchive/bulkimport/ImportLineParser.java
  - backend/src/main/java/de/moviearchive/bulkimport/BulkImportController.java:63
  - frontend/pages/add.vue:218
---

## Problem

Bulk import's file picker accepts `.txt,.csv` (`frontend/pages/add.vue:218`), and the "CSV" label implies real tabular/CSV parsing. In reality `BulkImportController.uploadBulkImport()` (line 63) just reads the uploaded file as raw UTF-8 text and splits it line-by-line; `ImportLineParser` then expects each line to be exactly `Title;OriginalTitle;Year`, semicolon-delimited, with no quoting support. There is no actual CSV parsing (no comma-delimiter support, no quoted-field handling, no header row). A real CSV exported from Excel/Numbers/Google Sheets (comma-separated) would not import correctly today — only a manually-authored semicolon-delimited plain-text file works.

Raised during Phase 10 UAT (2026-08-24): while confirming the accepted line format with the user (who also noted `.rtf` is correctly greyed out in the file picker), the user flagged that a plain-text-only format is "unprofessional" long-term and that bulk import should support proper CSV files — especially since a future CSV *export* of the archive is also planned and should use the same, consistent tabular format.

## Solution

TBD. Likely direction: adopt a real CSV parsing library (e.g. Apache Commons CSV, already common in the Spring ecosystem) for `ImportLineParser`/`BulkImportController`, supporting standard comma-delimited CSV with optional header row and quoted fields containing commas/semicolons. Should be designed jointly with the future CSV export feature so import and export use the same column schema and round-trip cleanly (export → re-import). Decide whether to keep the current semicolon plain-text format as a fallback/alternate input or replace it entirely.
