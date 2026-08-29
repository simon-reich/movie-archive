---
phase: 14
slug: wiki-batch-reload-pacing-cooldown-fix-progress-ui
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-27
---

# Phase 14 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Backend: JUnit 5 + Mockito + AssertJ + Testcontainers (`postgres:16-alpine`) + WireMock 3.13.0. Frontend: Vitest ^3.1.3 + Vue Test Utils |
| **Config file** | `backend/build.gradle.kts` (JUnit Platform, no separate config file); `frontend/vitest.config.ts` |
| **Quick run command** | Backend: `./gradlew test --tests "de.moviearchive.enrichment.*" --tests "de.moviearchive.admin.WikiReloadControllerTest"`. Frontend: `pnpm --filter frontend test -- settings imports-batchId` |
| **Full suite command** | Backend: `./gradlew test`. Frontend: `pnpm test` (`vitest run`) |
| **Estimated runtime** | ~120 seconds (backend, Testcontainers-backed) / ~30 seconds (frontend) |

---

## Sampling Rate

- **After every task commit:** Run the targeted `./gradlew test --tests <ClassName>` for backend files touched, or `pnpm test -- <filename-substring>` for frontend files touched
- **After every plan wave:** Run `./gradlew test` (backend full suite) + `pnpm test` (frontend full suite)
- **Before `/gsd-verify-work`:** Full suite must be green (both backend and frontend)
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 14-01-01 | 01 | 0 | D-14-02 | — | N/A | unit | `./gradlew test --tests WikiReloadServiceTest` | ✅ file exists, new case needed | ⬜ pending |
| 14-01-02 | 01 | 1 | D-14-01 | — | N/A | unit/integration | `./gradlew test --tests WikiReloadServiceIntegrationTest` | ✅ mechanism test exists; value assertion is a gap | ⬜ pending |
| 14-01-03 | 01 | 1 | D-14-02 | — | N/A | unit | `./gradlew test --tests WikiReloadServiceTest` | ✅ file exists | ⬜ pending |
| 14-02-01 | 02 | 0 | D-14-03 | T-14-01 | IDOR via `assertOwnership()` on new SSE endpoint | unit | `./gradlew test --tests WikiReloadProgressServiceTest` | ❌ W0 net-new | ⬜ pending |
| 14-02-02 | 02 | 1 | D-14-03, D-14-04 | T-14-01, T-14-03 | IDOR via `assertOwnership()` on new SSE + stop endpoints; header-based JWT via `@microsoft/fetch-event-source`, not native `EventSource` | integration | `./gradlew test --tests WikiReloadControllerTest` | ✅ file exists, new cases needed | ⬜ pending |
| 14-02-03 | 02 | 1 | D-14-04 | T-14-04 | Stop flag reset at top of `batchReload()` to prevent stale-flag no-op | integration | `./gradlew test --tests WikiReloadServiceIntegrationTest` | ✅ file exists, new cases needed | ⬜ pending |
| 14-03-01 | 03 | 1 | D-14-03 | T-14-02 | Header-based JWT auth on SSE subscribe, not query-param token | unit | `pnpm test -- useSettings` | ❌ W0 net-new | ⬜ pending |
| 14-03-02 | 03 | 2 | D-14-03, D-14-04 | — | N/A | component | `pnpm test -- settings` | ✅ file exists, new cases needed | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — add a case asserting `wikiLastAttemptedAt` is NOT set when `wikipediaClient.fetch(...)` throws a generic `Exception` (D-14-02); currently only success and `WikipediaNotFoundException` paths are covered
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadProgressServiceTest.java` — net-new, mirrors `BulkImportProgressServiceTest.java`'s register/publish/complete lifecycle test structure (`mock(SseEmitter.class)` + `ArgumentCaptor<SseEmitter.SseEventBuilder>`)
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — add cases for the new SSE progress endpoint and stop endpoint, including the ownership-check 403 case mirroring the existing `triggerReload()` pattern
- [ ] `frontend/test/unit/composables/useSettings.spec.ts` — add cases for a `subscribeToWikiReloadProgress`-equivalent function, mirroring `imports-batchId.spec.ts`'s `vi.mock('@/composables/...')` + captured-callback pattern
- [ ] `frontend/test/unit/pages/settings.spec.ts` — extend existing wiki-reload-trigger tests with progress block + Stop button rendering/interaction cases

---

## Manual-Only Verifications

*All phase behaviors have automated verification. (Real-world 429 pacing behavior at ~30s cadence was already confirmed via live verification in the Phase 13 follow-up that triggered this phase; no new manual-only check is required.)*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
