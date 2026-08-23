---
phase: 10
slug: bulk-import-engine
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-23
---

# Phase 10 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + AssertJ (unit), Testcontainers (Postgres, OpenSearch), WireMock 3.13.0 (TMDB stubbing), MockMvc (`@AutoConfigureMockMvc`) |
| **Config file** | `backend/src/test/resources/application-test.properties` |
| **Quick run command** | `./gradlew test --tests "*BulkImport*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30 seconds (quick) / full suite varies |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*BulkImport*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 10-01-XX | TBD | 0 | IMPORT-01 | V2/V4/V5 | Multipart upload accepted, returns 202, endpoint requires auth | Web/Controller | `./gradlew test --tests BulkImportControllerTest` | ❌ W0 | ⬜ pending |
| 10-01-XX | TBD | 0 | IMPORT-02 | V5 | Line parsed, TMDB searched, year-filtered | Unit (parser) + External API Contract (WireMock) | `./gradlew test --tests ImportLineParserTest` / `BulkImportServiceTest` | ❌ W0 | ⬜ pending |
| 10-01-XX | TBD | 0 | IMPORT-03 | V4 | Unique match auto-saved via `MovieService.initiate()`+`EnrichmentService.enrich()`, idempotent | Integration (`@SpringBootTest` + Testcontainers + WireMock) | `./gradlew test --tests BulkImportIntegrationTest` | ❌ W0 | ⬜ pending |
| 10-01-XX | TBD | 0 | IMPORT-04 | — | Multiple year-matches without unambiguous original-title narrowing → AMBIGUOUS, not auto-saved | Unit (matching algorithm) | `./gradlew test --tests BulkImportServiceTest` | ❌ W0 | ⬜ pending |
| 10-01-XX | TBD | 0 | IMPORT-07 | V4 | Re-upload of a saved line skips TMDB call and DB write entirely; non-saved lines retried | Integration (assert WireMock call count unchanged on 2nd upload) | `./gradlew test --tests BulkImportIntegrationTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky — planner fills in exact Task IDs / Plan numbers when PLAN.md files are created.*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` — covers IMPORT-02 (D-01/D-02/D-03 parsing edge cases: blank lines, missing OriginalTitle, non-numeric year, wrong field count)
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportServiceTest.java` — covers IMPORT-02/IMPORT-04 (matching algorithm: unique, ambiguous, narrowed-by-original-title, not-found)
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportIntegrationTest.java` — covers IMPORT-03/IMPORT-07 (full flow incl. dedup-skip-on-reupload, WireMock call-count assertions), mirrors `WikiReloadControllerTest`'s structure (WireMock + `AbstractOpenSearchTest`)
- [ ] Add `bulk-import.pacing-delay-ms=1` to `application-test.properties` — required before any integration test with >1 line is written, or the suite will slow down measurably
- [ ] TMDB fixture additions in `backend/src/test/resources/fixtures/tmdb/` for a multi-candidate (ambiguous) search response with matching `original_title` variants — none of the existing fixtures cover the ambiguous-search-results scenario this phase needs

---

## Manual-Only Verifications

*None — all phase behaviors have automated verification per the map above.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
