---
phase: 13
slug: wikidata-sparql-batch-lookup
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-26
---

# Phase 13 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + WireMock 3.13.0 + Testcontainers (postgres) |
| **Config file** | none — plain Gradle `test` task; per-test WireMock base URLs injected via `@DynamicPropertySource` (see `WikipediaClientTest.java:28-37`) |
| **Quick run command** | `./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30s quick / several minutes full (Testcontainers spin-up) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "de.moviearchive.movie.WikipediaClientTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 13-01-01 | 01 | 0 | D-01 | — | `resolveViaWikidataSparql()` resolves a 1-element IMDb ID list via SPARQL | unit (WireMock) | `./gradlew test --tests "*WikipediaClientTest*"` | ❌ W0 | ⬜ pending |
| 13-01-02 | 01 | 1 | D-02 | — | `batchReload()` prefetches via SPARQL batch before its per-movie loop, falls through on miss | unit/integration | `./gradlew test --tests "*WikiReloadService*"` | ❓ confirm at W0 | ⬜ pending |
| 13-01-03 | 01 | 1 | D-03 | — | `BulkImportService` two-pass: TMDB-detail-then-SPARQL-batch-then-per-line enrich | integration | `./gradlew test --tests "*BulkImportService*"` | ❓ confirm at W0 | ⬜ pending |
| 13-01-04 | 01 | 1 | D-04 | — | `logResolution()`/`resolutionLogPath` removed, no residual call sites | unit + compile | `./gradlew test --tests "*WikipediaClientTest*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-found.json` — SPARQL JSON response fixture, multiple IDs all resolve
- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-partial.json` — fixture, some IDs resolve and some don't
- [ ] `backend/src/test/resources/fixtures/wikidata-sparql/batch-empty.json` — fixture, zero bindings
- [ ] Confirm existence/coverage of `WikiReloadServiceTest.java` and `BulkImportServiceTest.java` (not located during research — needed before planning their specific edits)
- [ ] New test methods in `WikipediaClientTest.java`: single-ID batch call (1-element list), chunk-size boundary

---

## Manual-Only Verifications

*None — all phase behaviors have automated verification via WireMock fixtures.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
