---
phase: 07-polish-quality
plan: "03"
subsystem: ci-docs
tags: [ci, github-actions, playwright, e2e, readme, env-example]
dependency_graph:
  requires: [07-02]
  provides: [e2e-ci-workflow, env-example-e2e-vars, readme-e2e-section]
  affects: [.github/workflows/e2e-ci.yml, .env.example, README.md]
tech_stack:
  added: []
  patterns:
    - GitHub Actions E2E workflow with Docker Compose full-stack startup
    - SPRING_PROFILES_ACTIVE passthrough via docker compose env block
    - Playwright Chromium-only install (covers Desktop + Mobile Chrome projects)
key_files:
  created:
    - .github/workflows/e2e-ci.yml
  modified:
    - .env.example
    - README.md
decisions:
  - "Chromium-only Playwright install (--with-deps chromium) covers both Desktop Chrome and Mobile Chrome Pixel 5 projects — avoids downloading Firefox and WebKit in CI"
  - "Health check polls /api/actuator/health via Caddy proxy (not :8080 directly) — matches production routing and validates the full proxy chain"
  - "docker compose down -v removes volumes — each CI run starts with clean database and OpenSearch index"
  - "BACKEND_URL env var set to http://localhost/api in Playwright step so E2E beforeAll can reach /test/setup through Caddy proxy"
metrics:
  duration: "~2 min"
  completed: "2026-05-20"
  tasks_completed: 2
  files_changed: 3
---

# Phase 7 Plan 03: GitHub Actions E2E CI + Documentation Summary

**One-liner:** GitHub Actions E2E workflow starting the full Docker Compose stack and running Playwright on Chromium, plus `.env.example` and README documentation for the new E2E ENV vars.

## What Was Built

### Task 1 — GitHub Actions E2E CI workflow (25274fb)

- **`.github/workflows/e2e-ci.yml`** — triggers on push to `main` and any PR.
- Sets up pnpm 10 + Node.js 22 with cache on `frontend/pnpm-lock.yaml`.
- Installs only Chromium (`playwright install --with-deps chromium`) — covers both Desktop Chrome and Mobile Chrome Pixel 5 Playwright projects without downloading Firefox/WebKit.
- Starts full stack via `docker compose --profile app up -d` with these env vars:
  - `DB_PASSWORD`, `JWT_SECRET`, `ENCRYPTION_MASTER_KEY` — CI-safe placeholder values
  - `SPRING_PROFILES_ACTIVE: test` — activates the test seed endpoint in the backend container
  - `TEST_TMDB_KEY: ${{ secrets.TEST_TMDB_KEY }}` — real TMDB key from GitHub Actions secrets
- Health poll loop: 30 × 10s attempts on `http://localhost/api/actuator/health` (up to 5 min).
- Playwright run step sets `BASE_URL: http://localhost` and `BACKEND_URL: http://localhost/api`.
- Artifact upload: `frontend/playwright-report/` with 7-day retention, `if: always()`.
- Stack teardown: `docker compose --profile app down -v`, `if: always()`.

### Task 2 — .env.example + README.md documentation (96d0940)

- **`.env.example`** — appended E2E section with four new vars: `SPRING_PROFILES_ACTIVE=`, `TEST_USER_EMAIL=e2e@moviearchive.test`, `TEST_USER_PASSWORD=E2ePassword1!`, `TEST_TMDB_KEY=`. Existing vars untouched.
- **`README.md`** — inserted `## Running E2E Tests` section between `## Local Development` and `## SMTP Configuration`. Section includes:
  - Prerequisites (SPRING_PROFILES_ACTIVE + TEST_TMDB_KEY, start full stack, health check)
  - Run command (`cd frontend && pnpm test:e2e`)
  - Targeted run (`pnpm test:e2e --grep "Happy path"`)
  - CI subsection referencing `e2e-ci.yml` and the `TEST_TMDB_KEY` secret requirement
  - No additional sections added per D-15 (README stays minimal)

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | 25274fb | feat(07-03): GitHub Actions E2E CI workflow |
| 2 | 96d0940 | docs(07-03): E2E ENV vars in .env.example and Running E2E Tests section in README |

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — the workflow YAML and documentation are complete implementations. `TEST_TMDB_KEY` is intentionally empty in `.env.example` (user must supply their own key per the comment).

## Threat Flags

None — threat model in plan covers all introduced surfaces:
- T-7-03-01: `TEST_TMDB_KEY` referenced as `${{ secrets.TEST_TMDB_KEY }}` — never hardcoded
- T-7-03-02: `.env.example` has empty defaults for sensitive values — accepted
- T-7-03-03: `down -v` volume deletion is intentional for CI test isolation — accepted

## Self-Check: PASSED

- `.github/workflows/e2e-ci.yml` — exists, YAML valid (python3 yaml parse), all 8 required patterns present
- `.env.example` — contains all 4 new E2E vars (grep -c returns 4)
- `README.md` — contains `## Running E2E Tests`, `pnpm test:e2e`, `e2e-ci.yml` reference
- Commits 25274fb and 96d0940 verified in git log
