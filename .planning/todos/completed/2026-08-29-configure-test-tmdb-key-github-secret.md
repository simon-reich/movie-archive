---
created: 2026-08-29T00:00:00.000Z
title: Configure TEST_TMDB_KEY GitHub repo secret so E2E "add film" step gets real TMDB results
area: ci
severity: major
files:
  - .github/workflows/e2e-ci.yml
  - backend/src/main/resources/application-test.properties
  - frontend/test/e2e/happy-path.spec.ts
---

## Problem

The `TEST_TMDB_KEY` GitHub repository secret is not configured (`gh secret list` returns empty).
In `.github/workflows/e2e-ci.yml`, both the "Start full Docker Compose stack" step and the "Run
Playwright E2E tests" step set `TEST_TMDB_KEY: ${{ secrets.TEST_TMDB_KEY }}` — with no secret
configured, this resolves to an empty string `''`, which is passed through as a present-but-empty
env var to the backend container.

Unlike `test.user.email`/`test.user.password` (which have non-empty fallback defaults —
`${TEST_USER_EMAIL:e2e@moviearchive.test}` — in `application-test.properties`),
`test.tmdb.key=${TEST_TMDB_KEY:}` has **no non-empty fallback**. So when the secret is unset, the
backend's TMDB client has no real API key at all.

This means the real-TMDB-search step in `frontend/test/e2e/happy-path.spec.ts` (the "add film" /
poster-card visibility assertion, `await expect(page.locator('[data-testid="poster-card"]').first()).toBeVisible(...)`)
returns no results and times out waiting for a poster card that never appears.

This failure mode surfaced only after the separate login-redirect bug (see
`.planning/debug/resolved/e2e-login-redirect-flake.md`) was fixed — previously it was masked
because login itself always failed first, so the "add film" step was never reached.

**Evidence:** CI run 33270852363 (head SHA `eabdac9`) — login now succeeds and the test
progresses into the "add film" step, which times out on the poster-card locator because the real
TMDB search returns no results.

## Solution

The repo owner needs to configure a real TMDB API key as a GitHub Actions repository secret:

```
gh secret set TEST_TMDB_KEY
```

(or via GitHub UI: Settings → Secrets and variables → Actions → New repository secret)

with a real, valid TMDB API key value (obtained from https://www.themoviedb.org/settings/api).

No code or config changes are needed beyond this — the workflow already wires
`${{ secrets.TEST_TMDB_KEY }}` through to both the Docker Compose stack and the Playwright test
run env blocks correctly. This is purely a missing-secret issue, not a bug in the pipeline logic.

Do NOT attempt to obtain or fabricate a TMDB API key automatically — this requires the repo
owner's own TMDB account/credentials.

## Resolution

Repo owner configured `TEST_TMDB_KEY` via `gh secret set` on 2026-08-31. Confirmed via CI run
33382587145 (head SHA `16ab790`): E2E Tests completed with `conclusion: success` — first fully
green E2E run since the login-redirect and TMDB-secret issues were both resolved.
