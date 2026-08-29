---
phase: 8
slug: wiki-enrichment-tracking-batch-reload
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-22
---

# Phase 8 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + AssertJ + Testcontainers + WireMock (already on the backend test classpath) |
| **Config file** | `backend/build.gradle.kts` (test dependencies) — no separate JUnit config file |
| **Quick run command** | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~5-8 minutes (Testcontainers Postgres + OpenSearch) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 08-01-XX | 01 | 0 | ENRICH-01 (save-flow path) | — | N/A | unit/integration | `./gradlew test --tests "de.moviearchive.movie.EnrichmentServiceTest"` | ✅ exists, needs new assertions | ⬜ pending |
| 08-01-XX | 01 | 0 | ENRICH-01 (save-flow path) | — | N/A | integration | `./gradlew test --tests "de.moviearchive.movie.EnrichmentIntegrationTest"` | ✅ exists, needs new assertions | ⬜ pending |
| 08-01-XX | 01 | 0 | ENRICH-01 (batch-reload path) | — | N/A | unit | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceTest"` | ❌ Wave 0 — new file | ⬜ pending |
| 08-01-XX | 01 | 0 | ENRICH-02 | T-08-01 | IDOR — 403 on JWT-subject/path-userId mismatch (`assertOwnership`) | integration | `./gradlew test --tests "de.moviearchive.admin.WikiReloadControllerTest"` | ❌ Wave 0 — new file | ⬜ pending |
| 08-01-XX | 01 | 0 | ENRICH-03 | T-08-02 | N/A | integration | `./gradlew test --tests "de.moviearchive.movie.WikiReloadServiceIntegrationTest"` | ❌ Wave 0 — new file | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceTest.java` — Mockito unit test covering ENRICH-01 (success/failure timestamp paths) and per-movie exception isolation in `batchReload()`
- [ ] `backend/src/test/java/de/moviearchive/movie/WikiReloadServiceIntegrationTest.java` — covers ENRICH-02 eligibility filtering + ENRICH-03 pacing, extending `AbstractWireMockTest` + `AbstractOpenSearchTest`
- [ ] `backend/src/test/java/de/moviearchive/admin/WikiReloadControllerTest.java` — MockMvc controller test covering the 403 ownership check (style copied from `ReindexControllerTest.shouldReturn403_whenUserMismatch`)
- [ ] Extend existing `backend/src/test/java/de/moviearchive/movie/EnrichmentServiceTest.java` and `EnrichmentIntegrationTest.java` with `wikiLastAttemptedAt` assertions
- [ ] No new test framework/config install needed — all infrastructure (`AbstractWireMockTest`, `AbstractOpenSearchTest`, Testcontainers Postgres base) already exists and is reused as-is

---

## Manual-Only Verifications

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
