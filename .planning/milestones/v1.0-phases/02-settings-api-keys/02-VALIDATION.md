---
phase: 2
slug: settings-api-keys
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-16
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (BE), Vitest + @nuxt/test-utils (FE) |
| **Config file** | `backend/build.gradle.kts`, `frontend/vitest.config.ts` |
| **Quick run command** | `cd backend && ./gradlew test --tests "de.moviearchive.settings.*"` |
| **Full suite command** | `cd backend && ./gradlew test && cd ../frontend && pnpm test run` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd backend && ./gradlew test --tests "de.moviearchive.settings.*"`
- **After every plan wave:** Run `cd backend && ./gradlew test && cd ../frontend && pnpm test run`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 2-01-01 | 01 | 1 | SET-01 | T-2-01 | API keys stored AES-256-GCM, never returned plaintext | unit | `./gradlew test --tests "*.ApiKeyEncryptionTest"` | ❌ W0 | ⬜ pending |
| 2-01-02 | 01 | 1 | SET-02 | T-2-01 | OMDB key same encryption behavior | unit | `./gradlew test --tests "*.ApiKeyEncryptionTest"` | ❌ W0 | ⬜ pending |
| 2-02-01 | 02 | 1 | SET-03 | T-2-02 | Password change invalidates all sessions | integration | `./gradlew test --tests "*.PasswordChangeTest"` | ❌ W0 | ⬜ pending |
| 2-03-01 | 03 | 1 | SET-04 | T-2-03 | Email change sends verification to new address | integration | `./gradlew test --tests "*.EmailChangeTest"` | ❌ W0 | ⬜ pending |
| 2-04-01 | 04 | 2 | SET-05 | — | CSV export contains all user movie data | integration | `./gradlew test --tests "*.CsvExportTest"` | ❌ W0 | ⬜ pending |
| 2-04-02 | 04 | 2 | SET-06 | — | CSV import persists records correctly | integration | `./gradlew test --tests "*.CsvImportTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/settings/ApiKeyEncryptionTest.java` — stubs for SET-01, SET-02
- [ ] `backend/src/test/java/de/moviearchive/settings/PasswordChangeTest.java` — stubs for SET-03
- [ ] `backend/src/test/java/de/moviearchive/settings/EmailChangeTest.java` — stubs for SET-04
- [ ] `backend/src/test/java/de/moviearchive/settings/CsvExportTest.java` — stubs for SET-05
- [ ] `backend/src/test/java/de/moviearchive/settings/CsvImportTest.java` — stubs for SET-06

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Masked API key display in UI | SET-01, SET-02 | Visual verification of `****1234` mask format | Load settings page, verify keys show as masked with last 4 chars |
| Old email notification receipt | SET-04 | Email delivery check | Change email, verify Mailpit shows notification to old address |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
