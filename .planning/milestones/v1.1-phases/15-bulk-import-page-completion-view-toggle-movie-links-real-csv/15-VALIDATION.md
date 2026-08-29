---
phase: 15
slug: bulk-import-page-completion-view-toggle-movie-links-real-csv
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-28
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Backend framework** | JUnit 5 + AssertJ, Spring Boot Test (MockMvc, `@SpringBootTest`), Testcontainers (`postgres:16-alpine`), WireMock |
| **Backend config file** | `backend/build.gradle.kts` (`useJUnitPlatform()`, JaCoCo coverage gate at 75% line coverage) |
| **Backend quick run** | `cd backend && ./gradlew test --tests "de.moviearchive.bulkimport.*"` |
| **Backend full suite** | `cd backend && ./gradlew check` |
| **Frontend framework** | Vitest + `@vue/test-utils` |
| **Frontend config file** | `frontend/package.json` (`"test": "vitest run"`) |
| **Frontend quick run** | `cd frontend && pnpm vitest run test/unit/pages/imports-batchId.spec.ts` |
| **Frontend full suite** | `cd frontend && pnpm test` |

---

## Sampling Rate

- **After every task commit:** Run the relevant quick command (`ImportLineParserTest`, `BulkImportControllerTest`, or `imports-batchId.spec.ts`, depending on the file touched)
- **After every plan wave:** Run `./gradlew check` (backend) + `pnpm test` (frontend)
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~30 seconds (existing suites are fast; no watch-mode)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD-01 | 01 | 1 | D-01–D-04 (view toggle) | — | N/A | unit (Vitest) | `pnpm vitest run test/unit/pages/imports-batchId.spec.ts` | ✅ existing | ⬜ pending |
| TBD-02 | 01 | 1 | D-05, D-06 (movie links) | — | N/A | unit + integration | `pnpm vitest run ...` / `./gradlew test --tests "*BulkImportControllerTest"` | ✅ existing | ⬜ pending |
| TBD-03 | 01 | 2 | D-08–D-10 (inline resolve) | T-15-01 (IDOR via lineId) | 403/404 on line not owned by batch, `findByIdAndBatchId()` scoped query | integration (MockMvc + WireMock) | `./gradlew test --tests "*BulkImportControllerTest"` | ✅ existing, new cases needed | ⬜ pending |
| TBD-04 | 01 | 1 | D-11 (PARSE_ERROR raw line) | — | N/A | unit (Vitest) | `pnpm vitest run ...` | ✅ existing | ⬜ pending |
| TBD-05 | 02 | 1 | D-12–D-16 (CSV parsing) | T-15-02 (unguarded field access) | Malformed record (field count < 3) yields `PARSE_ERROR`, never uncaught exception | unit (JUnit) | `./gradlew test --tests "*ImportLineParserTest"` | ✅ existing, new cases needed | ⬜ pending |
| TBD-06 | 02 | — | D-17 (regression: legacy file still imports) | — | N/A | manual/UAT | N/A — real local file, not a CI fixture | manual only | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*(Task IDs are placeholders — the planner assigns real `{phase}-{plan}-{task}` IDs; this table's rows map 1:1 to plan decisions and should be reconciled once PLAN.md exists.)*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/bulkimport/ImportLineParserTest.java` — add `@Test` cases for `parseCsv()`: comma-delimited valid line, quoted comma-containing title (D-15), wrong field count, non-numeric year, header-row detection input.
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportControllerTest.java` — add cases for the new resolve endpoint (happy path, wrong-user 403, unknown lineId 404, other-batch's lineId rejected) and for `getBatchDetail()`'s new `id`/`movieId`/`rawLine` fields.
- [ ] `frontend/test/unit/pages/imports-batchId.spec.ts` — add cases for view-toggle persistence, movie-link `NuxtLink` target, inline-resolve widget flow, PARSE_ERROR raw-line rendering.
- [ ] No new test framework/fixture setup needed — both backend and frontend infrastructure already fully cover this phase's needs.

*No framework install gaps: both JUnit 5 + AssertJ + MockMvc + WireMock and Vitest + @vue/test-utils are already configured and exercised by the existing bulk-import test suites.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|--------------------|
| Legacy `Title;OriginalTitle;Year` file still imports correctly after CSV support is added | D-17 | Regression check against the user's real local file (`saubere_filmliste.txt`), not committed as a CI fixture | Run a bulk import against `saubere_filmliste.txt` post-change; confirm all lines resolve identically to pre-change behavior |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
