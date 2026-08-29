---
status: investigating
trigger: "CI failure on GitHub Actions run 33266244032 (push to main, commit 1cdaa77 'docs: update retrospective for v1.1') — Playwright E2E happy-path test failed on 2 of 4 shards"
created: 2026-08-29
updated: 2026-08-29
---

## Symptoms

- **Expected behavior**: After submitting the login form, `frontend/test/e2e/happy-path.spec.ts` expects the app to redirect to `http://localhost/` within 15s (comment in the test: "After successful login, app redirects to / (D-13)").
- **Actual behavior**: `page.toHaveURL('/')` times out after 15000ms; the browser is still at `http://localhost/login`. Failed on first attempt AND both retries (3 total attempts, same result each time) for [chromium] and [Mobile Chrome] projects. The [Desktop Chrome] equivalent (if a separate project) and the eventual run summary line show "2 passed (1.7m)" alongside "2 failed" — so 2 of the 4 shards/projects in this run passed the same test.
- **Error messages**:
  ```
  Error: expect(page).toHaveURL(expected) failed
  Expected: "http://localhost/"
  Received: "http://localhost/login"
  Timeout:  15000ms
  Call log:
    - Expect "toHaveURL" with timeout 15000ms
      34 × unexpected value "http://localhost/login"
  ```
  at `frontend/test/e2e/happy-path.spec.ts:27:24` (`await expect(page).toHaveURL('/', { timeout: 15_000 })`)
- **Timeline**: First observed on 2026-08-29 in the first real push to `origin/main` since 2026-05-20 (366 local commits, spanning the entire v1.1 milestone, were pushed in one batch just now). CI had not run against a real push in the interim — unknown how long this has actually been broken, since local dev/testing throughout v1.1 never exercised this specific CI job.
- **Reproduction**: Not yet reproduced locally. Triggered reliably (2/4 shards) in the single CI run so far. Full CI job command: `docker compose --profile app up` stack + `pnpm exec playwright test` (see `.github/workflows/*frontend*e2e*.yml` or equivalent for exact invocation — orchestrator has not yet located/read the workflow file).

## Current Focus

- **hypothesis**: (not yet formed — initial evidence gathering)
- **test**: (pending)
- **expecting**: (pending)
- **next_action**: gather initial evidence — read the E2E workflow YAML, `happy-path.spec.ts` lines ~1-30 (login flow), and the login page/composable's redirect logic to understand what could make the post-login redirect land back on /login intermittently across CI shards but not always

## Evidence

## Eliminated

## Resolution

- root_cause:
- fix:
- verification:
- files_changed:
