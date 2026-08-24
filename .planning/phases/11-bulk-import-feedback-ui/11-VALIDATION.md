---
phase: 11
slug: bulk-import-feedback-ui
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-24
---

# Phase 11 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (backend)** | JUnit 5 + Mockito + Testcontainers + WireMock, MockMvc (`AutoConfigureMockMvc`) |
| **Framework (frontend)** | Vitest + Vue Test Utils + `@nuxt/test-utils` + MSW |
| **Config file (backend)** | `backend/build.gradle.kts` (`useJUnitPlatform()`) |
| **Config file (frontend)** | `frontend/package.json` (`"test": "vitest run"`) |
| **Quick run command (backend)** | `./gradlew test --tests "*BulkImport*"` |
| **Quick run command (frontend)** | `pnpm --filter frontend test -- imports` |
| **Full suite command (backend)** | `./gradlew check` (includes JaCoCo coverage gate, 75% line minimum) |
| **Full suite command (frontend)** | `pnpm --filter frontend test` |
| **Estimated runtime** | ~90 seconds combined (backend + frontend targeted runs) |

---

## Sampling Rate

- **After every task commit:** Run the targeted quick command for the layer touched (backend `--tests` filter or frontend spec file)
- **After every plan wave:** Run `./gradlew check` + `pnpm --filter frontend test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 11-01-TBD | 01 | 0 | IMPORT-05 | — | POST upload returns `batchId` in response body | integration (MockMvc) | `./gradlew test --tests BulkImportControllerTest` | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-05 | — | SSE endpoint emits progress events with increasing `processed` count | integration | new `BulkImportProgressServiceTest` | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-05 | T-11-01 | SSE endpoint rejects a `batchId` not owned by the requesting user (403) | integration | new ownership test, mirrors `WikiReloadController` pattern | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-06 | — | `saveAndUpsert()` persists `poster_path` from the already-fetched TMDB match | unit (`BulkImportServiceTest`) | `./gradlew test --tests BulkImportServiceTest` | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-06 | — | `GET /movies/bulk-import/batches` returns batches ordered by `created_at DESC` with `statusCounts` | integration | new/extended `BulkImportBatchControllerTest` | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-06 | T-11-01 | `GET /movies/bulk-import/batches/{batchId}` returns per-line title/poster/status, 403s for another user's batch | integration | same as above | ❌ W0 | ⬜ pending |
| 11-01-TBD | 01 | 0 | IMPORT-06 | — | Batch-list and batch-detail pages render title/poster/status, incl. no-poster fallback | component (Vitest + `@nuxt/test-utils` + MSW) | `pnpm --filter frontend test -- imports` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Task IDs are placeholders (TBD) — the planner assigns final task IDs; this map should be reconciled against the final PLAN.md task list.*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportBatchControllerTest.java` (or extend existing) — covers IMPORT-05/IMPORT-06 endpoint behavior, including the 403 ownership case
- [ ] `backend/src/test/java/de/moviearchive/bulkimport/BulkImportProgressServiceTest.java` — covers the in-memory emitter registry (register/publish/complete/remove-on-timeout)
- [ ] `frontend/test/unit/pages/imports/index.spec.ts` — batch list page
- [ ] `frontend/test/unit/pages/imports/[batchId].spec.ts` — batch detail + progress page
- [ ] MSW handler additions for the new GET endpoints, and an SSE-mocking strategy for `@microsoft/fetch-event-source` (interceptable via the global `fetch`, same as existing MSW `$fetch` interception)

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
