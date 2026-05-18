---
phase: 6
slug: movie-detail-personal-fields
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-18
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (BE) / Vitest + Vue Test Utils (FE) |
| **Config file** | `backend/build.gradle.kts` / `frontend/vitest.config.ts` |
| **Quick run command** | `cd backend && ./gradlew test --tests "*MovieDetail*" -x integrationTest` |
| **Full suite command** | `cd backend && ./gradlew test && cd ../frontend && pnpm test run` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd backend && ./gradlew test --tests "*MovieDetail*" -x integrationTest`
- **After every plan wave:** Run `cd backend && ./gradlew test && cd ../frontend && pnpm test run`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 6-01-01 | 01 | 1 | DETAIL-01 | — | N/A | migration | `cd backend && ./gradlew flywayMigrate` | ❌ W0 | ⬜ pending |
| 6-01-02 | 01 | 1 | DETAIL-01 | — | JWT ownership check | integration | `cd backend && ./gradlew integrationTest --tests "*MovieDetailController*"` | ❌ W0 | ⬜ pending |
| 6-01-03 | 01 | 1 | DETAIL-01 | — | N/A | unit | `cd backend && ./gradlew test --tests "*MovieDetailService*"` | ❌ W0 | ⬜ pending |
| 6-02-01 | 02 | 1 | DETAIL-02 | — | JWT ownership; no cross-user update | integration | `cd backend && ./gradlew integrationTest --tests "*PersonalFields*"` | ❌ W0 | ⬜ pending |
| 6-02-02 | 02 | 1 | DETAIL-02 | — | N/A | unit | `cd backend && ./gradlew test --tests "*PersonalFields*"` | ❌ W0 | ⬜ pending |
| 6-03-01 | 03 | 1 | DETAIL-01 | — | JWT ownership; deletion idempotent | integration | `cd backend && ./gradlew integrationTest --tests "*DeleteMovie*"` | ❌ W0 | ⬜ pending |
| 6-04-01 | 04 | 2 | DETAIL-01 | — | N/A | component | `cd frontend && pnpm test run MovieDetail` | ❌ W0 | ⬜ pending |
| 6-04-02 | 04 | 2 | DETAIL-03 | — | N/A | component | `cd frontend && pnpm test run PersonalFields` | ❌ W0 | ⬜ pending |
| 6-04-03 | 04 | 2 | DETAIL-04 | — | N/A | component | `cd frontend && pnpm test run TrailerEmbed` | ❌ W0 | ⬜ pending |
| 6-04-04 | 04 | 2 | DETAIL-05 | — | N/A | component | `cd frontend && pnpm test run ClickableAttr` | ❌ W0 | ⬜ pending |
| 6-05-01 | 05 | 2 | DETAIL-01 | — | N/A | component | `cd frontend && pnpm test run MovieCard` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/.../MovieDetailControllerTest.java` — stubs for DETAIL-01 (GET /movies/{id})
- [ ] `backend/src/test/java/.../PersonalFieldsIntegrationTest.java` — stubs for DETAIL-02 (PATCH /movies/{id}/personal)
- [ ] `backend/src/test/java/.../DeleteMovieIntegrationTest.java` — stubs for DETAIL-01 delete endpoint
- [ ] `frontend/components/__tests__/MovieDetailPage.test.ts` — stubs for DETAIL-01 through DETAIL-05
- [ ] MSW handlers in `frontend/test/msw/handlers.ts` — GET /movies/{id}, PATCH /movies/{id}/personal, DELETE /movies/{id}

*Existing Testcontainers and Vitest infrastructure covers the framework layer. Only test files need to be created.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Backdrop/poster hero renders correctly with real TMDB images | DETAIL-01 | Image URL construction requires real CDN | Open detail page in browser, verify backdrop fills hero width and poster overlays left |
| YouTube lazy embed fires only on click | DETAIL-04 | Network behavior requires browser | Open detail page, verify no YouTube request in Network tab until thumbnail clicked |
| Notes auto-save debounce ~1s | DETAIL-02 | Timing behavior requires manual interaction | Type in notes field, wait 1s, verify PATCH request fires |
| Delete confirmation modal flow | DETAIL-01 | UI modal interaction | Click delete, confirm dialog text "Remove from archive? This cannot be undone.", confirm, verify redirect to /search |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
