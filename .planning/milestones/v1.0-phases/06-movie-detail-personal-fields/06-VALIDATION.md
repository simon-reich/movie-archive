---
phase: 6
slug: movie-detail-personal-fields
status: complete
nyquist_compliant: true
wave_0_complete: true
created: 2026-05-18
audited: 2026-05-19
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (BE) / Vitest + Vue Test Utils (FE) |
| **Config file** | `backend/build.gradle.kts` / `frontend/vitest.config.ts` |
| **Quick run command** | `cd backend && ./gradlew test --tests "*MovieDetail*"` |
| **Full suite command** | `cd backend && ./gradlew test && cd ../frontend && pnpm test run` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd backend && ./gradlew test --tests "*MovieDetail*"`
- **After every plan wave:** Run `cd backend && ./gradlew test && cd ../frontend && pnpm test run`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 6-01-01 | 01 | 1 | DETAIL-01 | — | N/A | integration | `cd backend && ./gradlew test --tests "*MovieDetailController*"` | ✅ | ✅ green |
| 6-01-02 | 01 | 1 | DETAIL-01 | — | JWT ownership check | integration | `cd backend && ./gradlew test --tests "*MovieDetailController*"` | ✅ | ✅ green |
| 6-01-03 | 01 | 1 | DETAIL-01 | — | N/A | integration | `cd backend && ./gradlew test --tests "*MovieDetailController*"` | ✅ | ✅ green |
| 6-02-01 | 02 | 1 | DETAIL-02 | — | JWT ownership; no cross-user update | integration | `cd backend && ./gradlew test --tests "*MovieDetailController*"` | ✅ | ✅ green |
| 6-02-02 | 02 | 1 | DETAIL-02 | — | N/A | unit | `cd frontend && npx vitest run test/unit/composables/useMovieDetail.spec.ts` | ✅ | ✅ green |
| 6-03-01 | 03 | 1 | DETAIL-01 | — | JWT ownership; deletion idempotent | integration | `cd backend && ./gradlew test --tests "*MovieDetailController*"` | ✅ | ✅ green |
| 6-04-01 | 04 | 2 | DETAIL-01 | — | N/A | component | `cd frontend && npx vitest run test/unit/pages/movies-id.spec.ts` | ✅ | ✅ green |
| 6-04-02 | 04 | 2 | DETAIL-03 | — | N/A | component | `cd frontend && npx vitest run test/unit/pages/movies-id.spec.ts` | ✅ | ✅ green |
| 6-04-03 | 04 | 2 | DETAIL-04 | — | N/A | component | `cd frontend && npx vitest run test/unit/components/TrailerEmbed.spec.ts` | ✅ | ✅ green |
| 6-04-04 | 04 | 2 | DETAIL-05 | — | N/A | component | `cd frontend && npx vitest run test/unit/pages/movies-id.spec.ts` | ✅ | ✅ green |
| 6-05-01 | 05 | 2 | DETAIL-01 | — | N/A | component | `cd frontend && npx vitest run test/unit/components/MovieCard.spec.ts` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `backend/src/test/java/de/moviearchive/movie/MovieDetailControllerTest.java` — GET, PATCH, DELETE coverage for DETAIL-01 through DETAIL-03
- [x] `frontend/test/unit/pages/movies-id.spec.ts` — DETAIL-01 through DETAIL-05 page component
- [x] `frontend/test/unit/composables/useMovieDetail.spec.ts` — composable unit tests
- [x] `frontend/test/unit/components/TrailerEmbed.spec.ts` — DETAIL-04 lazy embed
- [x] `frontend/test/unit/components/MovieCard.spec.ts` — DETAIL-01/DETAIL-05 navigation and poster URL
- [x] MSW handlers in `frontend/test/mocks/handlers/movieDetail.ts` — GET /movies/{id}, PATCH /movies/{id}/personal, DELETE /movies/{id}

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

- [x] All tasks have automated verify commands
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 requirements all met
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** 2026-05-19

---

## Validation Audit 2026-05-19

| Metric | Count |
|--------|-------|
| Gaps found | 1 |
| Resolved | 1 |
| Escalated | 0 |

*Gap resolved: 6-05-01 — added `frontend/test/unit/components/MovieCard.spec.ts` (5 tests: NuxtLink wrapping, posterUrl TMDB/placeholder, genre chip navigation, director chip navigation). All 11 tasks now have green automated coverage.*
