---
phase: 5
slug: search
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-17
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers (BE); Vitest 3.1.3 + @nuxt/test-utils + MSW (FE) |
| **BE config file** | `build.gradle.kts` (useJUnitPlatform()) |
| **FE config file** | `frontend/vitest.config.ts` |
| **BE quick run** | `cd backend && ./gradlew test --tests "de.moviearchive.search.*"` |
| **BE full suite** | `cd backend && ./gradlew test` |
| **FE quick run** | `cd frontend && pnpm test` |
| **FE full suite** | `cd frontend && pnpm test:coverage` |
| **Estimated runtime** | ~60–90 seconds BE integration suite; ~15 seconds FE unit suite |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "de.moviearchive.search.*"` (BE) + `pnpm test` (FE)
- **After every plan wave:** Run `./gradlew test` (full BE suite)
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds (BE integration tests dominate)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 0 | SRCH-01 | — | Stub scaffolding only | Wave 0 | `./gradlew test --tests "de.moviearchive.search.*"` | ❌ W0 | ⬜ pending |
| 05-01-02 | 01 | 0 | SRCH-01..04 | — | MSW handlers exist | Wave 0 | `pnpm test` | ❌ W0 | ⬜ pending |
| 05-02-01 | 02 | 1 | SRCH-01 | IDOR | All queries scoped to movies-{userId} | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldReturnAllFilms_whenQueryIsEmpty"` | ❌ W0 | ⬜ pending |
| 05-02-02 | 02 | 1 | SRCH-01 | — | Title match with custom_english_analyzer | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFindFilmByTitle"` | ❌ W0 | ⬜ pending |
| 05-02-03 | 02 | 1 | SRCH-01 | — | Accent-folded query | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldNormalizeAccentsInSearch"` | ❌ W0 | ⬜ pending |
| 05-02-04 | 02 | 1 | SRCH-02 | — | OR within genre filter | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByMultipleGenresOR"` | ❌ W0 | ⬜ pending |
| 05-02-05 | 02 | 1 | SRCH-02 | — | AND across filter groups | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldCombineGenreAndDirectorFilters"` | ❌ W0 | ⬜ pending |
| 05-02-06 | 02 | 1 | SRCH-02 | — | Year range filter | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldFilterByYearRange"` | ❌ W0 | ⬜ pending |
| 05-02-07 | 02 | 1 | SRCH-02 | — | Watched=false returns empty until Phase 6 | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldReturnEmpty_whenWatchedFilterApplied"` | ❌ W0 | ⬜ pending |
| 05-02-08 | 02 | 1 | SRCH-03 | — | Sort title A-Z | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldSortByTitleAscending"` | ❌ W0 | ⬜ pending |
| 05-02-09 | 02 | 1 | SRCH-03 | — | Sort personal_rating nulls last | Integration | `./gradlew test --tests "*.SearchControllerTest.shouldSortByPersonalRating_nullsLast"` | ❌ W0 | ⬜ pending |
| 05-03-01 | 03 | 2 | SRCH-04 | — | URL param → filter applied on mount | Unit (FE) | `pnpm test -- useSearch` | ❌ W0 | ⬜ pending |
| 05-03-02 | 03 | 2 | SRCH-04 | — | Clickable attribute sets URL param | Component (FE) | `pnpm test -- search` | ❌ W0 | ⬜ pending |
| 05-03-03 | 03 | 2 | SRCH-01 | — | Search page executes search on mount | Component (FE) | `pnpm test -- search` | ❌ W0 | ⬜ pending |
| 05-04-01 | 04 | 2 | Dashboard | — | Dashboard loads stats + movie of day | Component (FE) | `pnpm test -- index` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/src/test/java/de/moviearchive/search/SearchControllerTest.java` — @Disabled stubs for all SRCH-01 through SRCH-04 test methods listed above
- [ ] `frontend/test/mocks/handlers/search.ts` — MSW handlers for `POST /api/search`, `GET /api/dashboard`, `GET /api/search/autocomplete`
- [ ] `frontend/test/unit/composables/useSearch.spec.ts` — `.todo()` stubs for URL param reading tests
- [ ] `frontend/test/unit/pages/search.spec.ts` — `.todo()` stubs for search page tests
- [ ] `frontend/test/unit/pages/index.spec.ts` — `.todo()` stubs for dashboard tests

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live debounce feels responsive (300ms) | SRCH-01 | Browser UX — timing feel cannot be unit-tested | Open /search, type quickly, confirm results update ~300ms after last keypress |
| Grid/list view mode toggle persists across page reload | D-07 | localStorage/Pinia persistence across mount cycles | Toggle view, reload page, confirm same view is active |
| "Movie of the day" stays stable for the full day | D-03 | Date-seeded randomness — must remain stable over 24 hours | Check movie at 09:00 and again at 18:00 same day |
| Empty archive shows "Add your first film" CTA | Dashboard | Empty state UX — no films to assert against | Use a fresh test account with no saved films |
| Clickable attribute on search result card navigates correctly | SRCH-04 | Browser navigation + URL param encoding | Click an actor name on a result card, confirm /search?actor=... loads with pre-filtered results |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
