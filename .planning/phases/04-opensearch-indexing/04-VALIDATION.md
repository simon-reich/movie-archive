---
phase: 4
slug: opensearch-indexing
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-17
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
| **Estimated runtime** | ~60–90 seconds (OpenSearch container startup adds ~30s) |

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
| 04-01-01 | 01 | 1 | IDX-01,02,03 | — | N/A | Integration stub | `./gradlew test --tests "*IndexingIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | IDX-04 | IDOR | 403 on userId mismatch | Web stub | `./gradlew test --tests "*ReindexControllerTest*"` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | IDX-01 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldIndexFilm_afterPostgresPersist"` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | IDX-01 | — | indexed_at=null on OS failure | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldLeaveIndexedAtNull_whenOsFails"` | ❌ W0 | ⬜ pending |
| 04-02-03 | 02 | 2 | IDX-02 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldCreateIndex_whenNotExists"` | ❌ W0 | ⬜ pending |
| 04-02-04 | 02 | 2 | IDX-02 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNotThrow_whenIndexAlreadyExists"` | ❌ W0 | ⬜ pending |
| 04-02-05 | 02 | 2 | IDX-03 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldNormalizeAccents"` | ❌ W0 | ⬜ pending |
| 04-02-06 | 02 | 2 | IDX-03 | — | N/A | Integration | `./gradlew test --tests "*IndexingIntegrationTest*shouldStemEnglishWords"` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 3 | IDX-04 | IDOR | 403 when userId ≠ JWT subject | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturn403_whenUserMismatch"` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 3 | IDX-04 | — | N/A | Integration | `./gradlew test --tests "*ReindexControllerTest*shouldFullReindex"` | ❌ W0 | ⬜ pending |
| 04-03-03 | 03 | 3 | IDX-04 | — | N/A | Integration | `./gradlew test --tests "*ReindexControllerTest*shouldIndexOnlyPending"` | ❌ W0 | ⬜ pending |
| 04-03-04 | 03 | 3 | IDX-04 | — | N/A | Web (MockMvc) | `./gradlew test --tests "*ReindexControllerTest*shouldReturnIndexedCount"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/AbstractOpenSearchTest.java` — OpenSearch Testcontainers base class (GenericContainer opensearchproject/opensearch:2.19.0, DISABLE_SECURITY_PLUGIN=true, discovery.type=single-node)
- [ ] `backend/src/test/java/de/moviearchive/indexing/IndexingIntegrationTest.java` — stubs covering IDX-01, IDX-02, IDX-03
- [ ] `backend/src/test/java/de/moviearchive/admin/ReindexControllerTest.java` — stubs covering IDX-04
- [ ] `backend/src/main/resources/opensearch/movies-index.json` — index definition JSON resource (custom analyzer + 40+ field mapping)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| OpenSearch index visible in Docker after save | IDX-01 | Requires running stack | `docker exec <opensearch-container> curl -s localhost:9200/movies-{userId}/_count` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
