---
phase: 05-search
fixed_at: 2026-05-18T00:00:00Z
review_path: .planning/phases/05-search/05-REVIEW.md
iteration: 1
findings_in_scope: 5
fixed: 4
skipped: 1
status: partial
---

# Phase 05: Code Review Fix Report

**Fixed at:** 2026-05-18T00:00:00Z
**Source review:** .planning/phases/05-search/05-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 5
- Fixed: 4
- Skipped: 1

## Fixed Issues

### WR-01: Integer overflow when computing `from` for deep pagination

**Files modified:** `backend/src/main/java/de/moviearchive/search/SearchService.java`
**Commit:** cc6dd2d
**Applied fix:** Replaced `int from = page * PAGE_SIZE` with long arithmetic: `long fromLong = (long) page * PAGE_SIZE`, added a guard that throws `IllegalArgumentException` when `fromLong > 10_000`, then casts safely to `int from`. This prevents silent overflow and produces a clean 400 before hitting OpenSearch.

---

### WR-02: `actors` filter sent as comma-joined string but backend expects single text-match

**Files modified:** `frontend/composables/useSearch.ts`
**Commit:** 6742bb1
**Applied fix:** Changed `actors` computed from `normalizeQueryParam(route.query.actors)` (returns `string[]`) to `paramAsString(route.query.actors)` (returns `string`), consistent with how `director` is handled. Updated `buildFilters` to use `if (actors.value) f.actors = actors.value` instead of the array join. Removes the comma-delimited multi-value inconsistency.

---

### WR-03: `useDashboard` swallows fetch errors silently — no error state exposed

**Files modified:** `frontend/composables/useDashboard.ts`, `frontend/pages/index.vue`
**Commit:** f7d2ef2
**Applied fix:** Added `error = ref<string | null>(null)` to `useDashboard`. Added `error.value = null` reset at the start of `fetchDashboard` and a `catch` block that sets `error.value = 'Failed to load dashboard. Please refresh.'`. Exposed `error` in the return value. In `index.vue`, destructured `error` from `useDashboard()` and added a `v-else-if="error"` branch before the empty-state branch to display the error message using `text-destructive` styling.

---

### WR-04: `FilterPanel` actors inconsistency (connected to WR-02 fix)

**Files modified:** `frontend/components/FilterPanel.vue`
**Commit:** 2abf71f
**Applied fix:** After WR-02 changed `actors` to return a `string`, updated `FilterPanel.vue` in two places: (1) initialized `actorsInput` from `actors.value` instead of the empty string literal, so the input is pre-populated when navigating to the page with an existing `actors` URL param; (2) fixed `hasActiveFilters` to use `actors.value` (truthy string check) instead of `actors.value.length > 0`, which was a type-incorrect array length check that would have produced wrong results with a string value.

---

## Skipped Issues

### WR-05: `DashboardService` annotated `@Transactional` but should be `@Transactional(readOnly = true)`

**File:** `backend/src/main/java/de/moviearchive/search/DashboardService.java:35`
**Reason:** skipped: code context differs from review — the fix is already applied. The current file has `@Transactional(readOnly = true)` at line 35, which is exactly what the review recommended. No change needed.
**Original issue:** `@Transactional` (read-write) on a read-only service unnecessarily holds a connection from the pool during OpenSearch I/O calls.

---

_Fixed: 2026-05-18T00:00:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
