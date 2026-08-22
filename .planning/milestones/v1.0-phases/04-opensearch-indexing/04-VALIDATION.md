---
phase: 4
slug: opensearch-indexing
status: complete
nyquist_compliant: true
wave_0_complete: true
created: 2026-05-17
audited: 2026-05-17
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers + AssertJ |
| **Config file** | `backend/build.gradle.kts` (JUnit Platform enabled) |
| **Quick run command** | `./gradlew test --tests "de.moviearchive.indexing.*" --tests "de.moviearchive.admin.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60–90 seconds (OpenSearch container startup adds ~30s; `withReuse(true)` speeds re-runs) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "de.moviearchive.indexing.*" --tests "de.moviearchive.admin.*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | IDX-01,02,03 | — | N/A | Integration stub | `./gradlew test --tests "*IndexingIntegrationTest*"` | ✅ | ✅ green |
| 04-01-02 | 01 | 1 | IDX-04 | IDOR | 403 on userId mismatch | Web stub | `./gradlew test --tests "*ReindexControllerTest*"` | ✅ | ✅ green |
| 04-02-01 | 02 | 2 | IDX-01 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldIndexFilm_afterPostgresPersist"` | ✅ | ✅ green |
| 04-02-02 | 02 | 2 | IDX-01 | — | indexed_at=null on OS failure | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldLeaveIndexedAtNull_whenOsFails"` | ✅ | ✅ green |
| 04-02-03 | 02 | 2 | IDX-02 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldCreateIndex_whenNotExists"` | ✅ | ✅ green |
| 04-02-04 | 02 | 2 | IDX-02 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNotThrow_whenIndexAlreadyExists"` | ✅ | ✅ green |
| 04-02-05 | 02 | 2 | IDX-03 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNormalizeAccents"` | ✅ | ✅ green |
| 04-02-06 | 02 | 2 | IDX-03 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldStemEnglishWords"` | ✅ | ✅ green |
| 04-03-01 | 03 | 3 | IDX-04 | IDOR | 403 when userId ≠ JWT subject | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturn403_whenUserMismatch"` | ✅ | ✅ green |
| 04-03-02 | 03 | 3 | IDX-04 | — | N/A | Integration | `./gradlew test --tests "*ReindexControllerTest*shouldFullReindex"` | ✅ | ✅ green |
| 04-03-03 | 03 | 3 | IDX-04 | — | N/A | Integration | `./gradlew test --tests "*ReindexControllerTest*shouldIndexOnlyPending"` | ✅ | ✅ green |
| 04-03-04 | 03 | 3 | IDX-04 | — | N/A | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturnIndexedCount"` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` — OpenSearch Testcontainers base class (GenericContainer opensearchproject/opensearch:2.19.0, DISABLE_SECURITY_PLUGIN=true, discovery.type=single-node)
- [x] `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` — stubs covering IDX-01, IDX-02, IDX-03
- [x] `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — stubs covering IDX-04
- [x] `backend/src/main/resources/opensearch/movies-index.json` — index definition JSON resource (custom analyzer + 40+ field mapping)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| OpenSearch index visible in Docker after save | IDX-01 | Requires running stack | `docker exec <opensearch-container> curl -s localhost:9200/movies-{userId}/_count` |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** ✅ complete

---

## Validation Audit 2026-05-17

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |
| Tasks audited | 12 |
| Tasks COVERED | 12 |
| Tasks PARTIAL | 0 |
| Tasks MISSING | 0 |

All 12 tasks have implemented, enabled tests confirmed green by 04-03-SUMMARY (10 integration tests, 0 failures). No new test files required.
