---
phase: 09-manual-wiki-retry
verified: 2026-08-23T15:15:28Z
status: passed
score: 7/7 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 9: Manual Wiki Retry Verification Report

**Phase Goal:** Users can manually retry Wikipedia enrichment for a single film from its detail page and immediately see whether it succeeded.
**Verified:** 2026-08-23T15:15:28Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | On the detail page of a film without Wikipedia data, the user sees a Retry button | ✓ VERIFIED | `frontend/pages/movies/[id].vue:348-362` — `v-else` block sibling to the wiki-data `v-if` renders "No Wikipedia data found." + Retry button. Confirmed by DOM-mounted test `movies-id.spec.ts:223-229 "shows Retry button when both wikipediaPlot and wikipediaCritics are null"` and inverse test at line 231. |
| 2 | Clicking Retry triggers a single Wikipedia enrichment attempt for that film only (not a batch) | ✓ VERIFIED | `MovieDetailService.retryWiki()` (`backend/.../MovieDetailService.java:120-125`) calls `wikiReloadService.retryWikipedia(movie)` — the single-movie method, distinct from `batchReload(UUID userId)` which iterates `findEligibleForWikiReload`. Backend test `retryWiki_returnsUpdatedDetail_onWikipediaSuccess` confirms one WireMock-stubbed Wikipedia call updates only the targeted movie. Frontend click wired via `onRetryWiki()` → `retryWiki()` → `POST /movies/{id}/retry-wiki`, confirmed by `movies-id.spec.ts:237-244`. |
| 3 | On success, the film's Wikipedia plot/critics data appears on the page | ✓ VERIFIED | Backend: `retryWiki_returnsUpdatedDetail_onWikipediaSuccess` asserts `$.wikipediaPlot`/`$.wikipediaUrl` non-empty in the response. Frontend composable does a full `movie.value = data` replace (`useMovieDetail.ts:113-127`, tested in `useMovieDetail.spec.ts` "replaces movie.value on success"). Template `v-if="movie.wikipediaPlot || movie.wikipediaCritics"` (`[id].vue:337`) independently confirmed to render plot/critics text when populated (`movies-id.spec.ts:111` "renders Wikipedia plot section when wikipediaPlot is non-null"). Full data-flow chain traced end-to-end. |
| 4 | On failure, the user sees a clear message that no Wikipedia data was found, and `wiki_last_attempted_at` is updated so the batch-reload cooldown reflects the manual attempt too | ✓ VERIFIED | Backend `retryWiki_returnsNoWikiFields_whenWikipediaNotFound` (all 6 fallback candidates exhausted via WireMock) asserts 200 with no wiki fields and `movieRepository.findById(...).getWikiLastAttemptedAt()` not null. `WikiReloadService.retryWikipedia()` sets `wikiLastAttemptedAt` on every attempt (`WikiReloadService.java:74`, both success and exception branches persist it). Same field is read by `MovieRepository.findEligibleForWikiReload(userId, cutoff)` (Phase 8's batch cooldown query), so a manual attempt correctly feeds the shared cooldown model. Frontend shows "Still no page found." after a failed retry, confirmed by `movies-id.spec.ts:246-258`, and the button re-enables (spinner/disabled state confirmed by `movies-id.spec.ts:260-268`). |

**Score:** 4/4 roadmap success criteria verified (all behaviorally, via passing tests — not presence-only)

### Requirement IDs (ENRICH-04, ENRICH-05) — PLAN Frontmatter Must-Haves

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 5 | `wiki_last_attempted_at` updated via reused `WikiReloadService.retryWikipedia(Movie)` (unmodified from Phase 8) | ✓ VERIFIED | `WikiReloadService.java` file untouched by this phase's commits; `MovieDetailService.retryWiki()` calls it directly. |
| 6 | Retry endpoint is ownership-scoped — a movie owned by a different user returns 404, never 200 with someone else's data | ✓ VERIFIED | `movieRepository.findByIdAndUserId(movieId, userId)` (`MovieDetailService.java:121-122`), 404 on absence. Backend test `retryWiki_returns404WhenMovieNotOwnedByUser` (`MovieDetailControllerTest.java:570-581`) passing. |
| 7 | Manual retry never checks or is blocked by the 30-day batch cooldown | ✓ VERIFIED | `retryWiki()` calls `wikiReloadService.retryWikipedia(movie)` directly — never calls `findEligibleForWikiReload`/cooldown-filtered path. Confirmed by code inspection; no cooldown check present in `MovieDetailService.retryWiki`. |

**Score:** 7/7 truths verified total (4 roadmap SCs + 3 additional PLAN must-haves; 09-02's must-haves for the folded Settings batch-reload trigger button are covered below under Additional Scope)

### Additional Scope: 09-02 Settings Batch-Reload Trigger (folded todo, same requirement IDs)

Plan 09-02 was bundled into this phase and also declares `requirements: [ENRICH-04, ENRICH-05]` — it delivers `GET /users/me` and a Settings-page "Reload missing Wikipedia data" button that triggers Phase 8's existing batch endpoint. This is additive scope beyond the roadmap's phase goal/success-criteria text (which describes only the single-film retry flow), but since it shares requirement IDs and was executed under this phase, it is verified here for completeness:

| Truth | Status | Evidence |
|-------|--------|----------|
| `GET /users/me` returns only `{ id }`, never the User entity/password hash | ✓ VERIFIED | `UserController.java:29-36` returns `Map.of("id", id)`. Backend test `me_responseContainsOnlyIdField` passing (4/4 UserControllerTest tests pass). |
| Settings page fetches user id once (cached) and reuses it for the reload trigger | ✓ VERIFIED | `useSettings.ts:14-27` `getCurrentUserId()` — fetch-once-cache-in-ref pattern. Test `useSettings.spec.ts` "fetches GET /api/users/me once and caches the result" passing. |
| Reload button shows 202/503/generic-failure outcomes distinctly, never silently stuck | ✓ VERIFIED | `settings.vue:177-190` `onTriggerWikiReload()` try/catch/finally covers all three outcomes; `useSettings.ts:32-46` `triggerWikiReload()` maps 503→'already-running', rethrows others. Tests passing in both `useSettings.spec.ts` and `settings.spec.ts`. |

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/java/de/moviearchive/movie/MovieDetailController.java` | `POST /{id}/retry-wiki` endpoint | ✓ VERIFIED | Present, wired, ownership-scoped via `resolveUserId(auth)` |
| `backend/src/main/java/de/moviearchive/movie/MovieDetailService.java` | `retryWiki(userId, movieId)` orchestration | ✓ VERIFIED | Present, calls `wikiReloadService.retryWikipedia(movie)`, returns `getDetail(...)` |
| `frontend/composables/useMovieDetail.ts` | `retryWiki()` + `wikiRetrying` ref | ✓ VERIFIED | Present, full `movie.value` replace on success, silent-swallow on error |
| `frontend/pages/movies/[id].vue` | Retry button UI, `v-else` block | ✓ VERIFIED | Present at lines 348-362, wired to `onRetryWiki()` |
| `backend/src/main/java/de/moviearchive/user/UserController.java` | `GET /users/me` | ✓ VERIFIED | Present, new file, returns minimal id map |
| `frontend/composables/useSettings.ts` | `getCurrentUserId()` / `triggerWikiReload()` | ✓ VERIFIED | Present, both exported |
| `frontend/pages/settings.vue` | `<section id="wikipedia-data">` | ✓ VERIFIED | Present at line 409 with `ButtonPrimary` bound to `onTriggerWikiReload` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `MovieDetailController.retryWiki` | `MovieDetailService.retryWiki` | direct call | ✓ WIRED | `movieDetailService.retryWiki(userId, id)` |
| `MovieDetailService.retryWiki` | `WikiReloadService.retryWikipedia(Movie)` | direct call, unmodified Phase 8 method | ✓ WIRED | Confirmed at `MovieDetailService.java:123` |
| `frontend onRetryWiki()` | `useMovieDetail().retryWiki()` | await call | ✓ WIRED | `[id].vue:22-25` |
| `useMovieDetail().retryWiki()` | `POST /api/movies/{id}/retry-wiki` | `$fetch` | ✓ WIRED | `useMovieDetail.ts:116-120` |
| Response → `movie.value` (wholesale replace) | template `v-if` Wikipedia section | reactive Vue binding | ✓ WIRED / ✓ FLOWING | `movie.value = data` (composable) feeds `v-if="movie.wikipediaPlot \|\| movie.wikipediaCritics"` ([id].vue:337) — confirmed by independent tests on both halves of the chain |
| `settings.vue onTriggerWikiReload()` | `useSettings().triggerWikiReload()` | await call | ✓ WIRED | `settings.vue:181` |
| `triggerWikiReload()` | `getCurrentUserId()` → `GET /api/users/me` (cached) → `POST /api/admin/wiki-reload/{userId}` | sequential calls | ✓ WIRED | `useSettings.ts:32-46` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `MovieDetailService.retryWiki` response | `wikipediaPlot`/`wikipediaUrl` | `movie.getWikiPlot()`/`getWikiUrl()` after `wikiReloadService.retryWikipedia(movie)` mutates the entity from a real WireMock-stubbed Wikipedia fetch | Yes | ✓ FLOWING |
| `movies/[id].vue` Wikipedia section | `movie.wikipediaPlot`/`wikipediaCritics` | `useMovieDetail().movie` ref, replaced wholesale from the retry endpoint's JSON response | Yes | ✓ FLOWING |
| `UserController.me` response | `id` | `userRepository.findByEmail(email).getId()` — real Postgres-backed lookup, JWT-derived email | Yes | ✓ FLOWING |
| `settings.vue` reload button | `triggerWikiReload()` outcome message | `POST /api/admin/wiki-reload/{userId}` (existing Phase 8 endpoint, unchanged) | Yes | ✓ FLOWING |

### Behavioral Spot-Checks / Test Execution

Full targeted test suites were executed directly by the verifier (not taken from SUMMARY.md claims):

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Backend retry-wiki + users/me tests | `cd backend && DOCKER_HOST=unix:///Users/simonreich/.orbstack/run/docker.sock ./gradlew test --tests "MovieDetailControllerTest" --tests "UserControllerTest"` | BUILD SUCCESSFUL; `MovieDetailControllerTest` 17/17 passed, `UserControllerTest` 4/4 passed (verified via JUnit XML report `tests="17" failures="0" errors="0"` and `tests="4" failures="0" errors="0"`) | ✓ PASS |
| Frontend retry-wiki + settings tests | `cd frontend && pnpm exec vitest run test/unit/pages/movies-id.spec.ts test/unit/composables/useMovieDetail.spec.ts test/unit/composables/useSettings.spec.ts test/unit/pages/settings.spec.ts` | 4 files, 50/50 tests passed | ✓ PASS |
| Retry button DOM render + click + spinner + "Still no page found." | Same vitest run, DOM-mounted `mountPage()` assertions | 6 dedicated tests passing (button visibility x2, click invocation, failure note, spinner/disabled) | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| ENRICH-04 | 09-01, 09-02 | User can click a Retry button on a film's detail page that triggers a single enrichment attempt | ✓ SATISFIED | `POST /movies/{id}/retry-wiki`, Retry button UI, backend+frontend tests passing |
| ENRICH-05 | 09-01, 09-02 | Retry button shows result (success: data appears; failure: message + `wiki_last_attempted_at` updated) | ✓ SATISFIED | Success/failure paths both tested; `wikiLastAttemptedAt` persisted on every attempt |

No orphaned requirements — `REQUIREMENTS.md` traceability table maps both ENRICH-04 and ENRICH-05 to Phase 9 only, and both plan frontmatters declare exactly these two IDs.

### Anti-Patterns Found

None. Scanned all 7 modified/created files (`MovieDetailService.java`, `MovieDetailController.java`, `useMovieDetail.ts`, `movies/[id].vue`, `UserController.java`, `useSettings.ts`, `settings.vue`) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER`, empty-implementation, and hardcoded-empty-data patterns. The only `placeholder` matches found are legitimate HTML `placeholder=` input attributes (form UX hints), not stub markers.

### Human Verification Required

None. All roadmap success criteria and PLAN must-haves are verified through passing automated tests exercised directly by the verifier (backend integration tests hitting real Postgres + WireMock-stubbed Wikipedia; frontend DOM-mounted component tests). No visual-only, real-time, or external-service behavior remained unverifiable.

### Gaps Summary

No gaps. All 4 roadmap success criteria are met with real, wired, behaviorally-tested code:
1. Retry button appears conditionally on missing Wikipedia data — DOM-tested.
2. Retry triggers exactly the single-movie `WikiReloadService.retryWikipedia(Movie)` method, not the batch path — code + integration-tested.
3. Success flows the full response through a wholesale `movie.value` replace into the reactive template, rendering Plot/Critical Response inline — integration + unit + component tested.
4. Failure surfaces "Still no page found." client-side and persists `wiki_last_attempted_at` server-side, which the (unmodified) Phase 8 cooldown query already reads — integration-tested end to end.

Phase 09-02's folded-in Settings batch-reload trigger (additional scope, same requirement IDs) is also fully verified: `GET /users/me` never leaks the password hash, the button correctly distinguishes 202/503/generic-failure outcomes, and no code path leaves it silently stuck.

---

_Verified: 2026-08-23T15:15:28Z_
_Verifier: Claude (gsd-verifier)_
