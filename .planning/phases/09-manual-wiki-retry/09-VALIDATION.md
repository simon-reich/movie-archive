---
phase: 09
slug: manual-wiki-retry
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-23
---

# Phase 09 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Backend: JUnit 5 + Mockito + Testcontainers (Spring Boot Test) · Frontend: Vitest + Vue Test Utils + MSW |
| **Config file** | `backend/build.gradle.kts` (test deps) · `frontend/vitest.config.ts` |
| **Quick run command** | Backend: `./gradlew test --tests "*Wiki*"` · Frontend: `pnpm test -- --run movies/id` |
| **Full suite command** | Backend: `./gradlew test` · Frontend: `pnpm test` |
| **Estimated runtime** | ~60s (backend, Testcontainers) / ~15s (frontend, vitest) |

---

## Sampling Rate

- **After every task commit:** Run quick run command for the layer touched
- **After every plan wave:** Run both full suite commands (`./gradlew test` and `pnpm test`)
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 09-01-01 | 01 | 1 | ENRICH-04 | — | Retry endpoint only mutates the caller's own film (ownership check via `findByIdAndUserId`) | unit/integration | `./gradlew test --tests "*RetryWiki*"` | ✅ | ⬜ pending |
| 09-01-02 | 01 | 1 | ENRICH-05 | — | On failure, `wikiLastAttemptedAt` still updates so batch cooldown reflects the attempt | integration | `./gradlew test --tests "*RetryWiki*"` | ✅ | ⬜ pending |
| 09-02-01 | 02 | 1 | ENRICH-04 | — | N/A | component | `pnpm test -- --run movies/id` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*Existing infrastructure covers all phase requirements — Phase 8 already established the WireMock/MockMvc pattern for wiki enrichment endpoints and the frontend movie-detail page test harness.*

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
