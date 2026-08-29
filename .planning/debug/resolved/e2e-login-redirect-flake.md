---
status: resolved
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

bug_class: Bohrbug (deterministic — reproduced 100% across 2 independent CI runs, both projects, all 3 attempts each)

reasoning_checkpoint:
  hypothesis: "The E2E test user seeded by TestSetupController ends up with email='' and password=bcrypt('') instead of 'e2e@moviearchive.test'/'E2ePassword1!', because docker-compose.yml passes TEST_USER_EMAIL/TEST_USER_PASSWORD to the backend container using `${VAR:-}` substitution (empty-string default) when the GH Actions workflow step never sets those vars — this makes Spring see the env var as PRESENT-BUT-EMPTY, which shadows application-test.properties' own `${TEST_USER_EMAIL:e2e@moviearchive.test}` placeholder default (Spring only applies a `:default` fallback when the referenced property is entirely absent, not when it resolves to ''). The frontend test still submits the correct hardcoded 'e2e@moviearchive.test'/'E2ePassword1!' (its own env vars are never set either, so its `??` fallback in happy-path.spec.ts applies normally, since JS `??` treats `undefined` differently than Spring treats ''), so login fails with a genuine 401 (user not found by that email), which the UI displays as 'Invalid email or password.' and the page never leaves /login."
  confirming_evidence:
    - "Downloaded playwright-report from run 33266244032 and extracted the retry1 trace's error-context.md: DOM snapshot shows `alert: Invalid email or password.` on the /login page at the moment of the toHaveURL timeout — this is the literal string returned by AuthController's BadCredentialsException handler, proving the redirect never happens because login itself returned 401, not because of a UI/redirect timing race."
    - "Extracted 1-trace.network from the same trace: POST http://localhost/api/auth/login request body is exactly `{\"email\":\"e2e@moviearchive.test\",\"password\":\"E2ePassword1!\"}` (correct, matches TEST_EMAIL/TEST_PASSWORD spec defaults) and the response is `401 Unauthorized` with body `{\"message\":\"Invalid email or password.\"}`."
    - "0-trace.network (APIRequestContext trace) shows the beforeAll's POST /api/test/setup returned 200 OK ~635ms before the failing login POST, in the same worker/trace — so the user-seed call itself succeeded; the failure is in what it seeded, not a timing race with the login call."
    - "docker-compose.yml backend.environment sets `TEST_USER_EMAIL: ${TEST_USER_EMAIL:-}` / `TEST_USER_PASSWORD: ${TEST_USER_PASSWORD:-}` (map-style, forces the key present in the container env even when unset on the host, substituting ''), while `.github/workflows/e2e-ci.yml`'s 'Start full Docker Compose stack' step env block does NOT set TEST_USER_EMAIL/TEST_USER_PASSWORD at all — confirmed by reading both files."
    - "application-test.properties defines `test.user.email=${TEST_USER_EMAIL:e2e@moviearchive.test}` — Spring's placeholder `:default` syntax only applies when the referenced property is absent from the Environment, not when it resolves to an empty string; since TEST_USER_EMAIL is present (empty) in the container, `test.user.email` resolves to ''."
    - "Re-ran the same E2E workflow (new push, run 33270109258) and it failed identically (401/Invalid email or password, same DOM alert) — 2 for 2 CI runs, both projects, all retries — this is a deterministic Bohrbug, not a real 'flake'; the debug session title is a misnomer from before root cause was known."
    - "git log -p on docker-compose.yml shows the TEST_USER_EMAIL/PASSWORD/TMDB_KEY lines were added in commit 7c4ab03 ('fix(e2e): make Playwright happy-path tests pass reliably') using the `${VAR:-}` empty-default pattern — written and committed during local v1.1 development, never exercised against a real GitHub Actions run until this week's first real push (per Symptoms.started), which is why it was never caught."
  falsification_test: "If the hypothesis is correct, curling `POST /api/test/setup` then `POST /api/auth/login` with email='' (empty string) directly against a docker-compose-app-profile-started backend (with TEST_USER_EMAIL unset in the invoking shell) should succeed with 200 and return the user's email as ''; logging into the UI with the literal empty string as the email/password should succeed where 'e2e@moviearchive.test'/'E2ePassword1!' fails. This is falsifiable by inspecting a live container's env (`docker compose exec backend env | grep TEST_USER`) or by checking Postgres `select email from users` after /test/setup runs in that environment — expect email=''."
  fix_rationale: "Change docker-compose.yml's backend.environment entries for TEST_USER_EMAIL and TEST_USER_PASSWORD from map-style `KEY: ${KEY:-}` (which always injects the key, forcing '' when unset) to list-style passthrough `- KEY` (Compose only sets the container env var when the host shell has it set; omits the key entirely otherwise). This removes the conflicting empty-string default layer entirely, letting application-test.properties' own default ('e2e@moviearchive.test'/'E2ePassword1!') be the single source of truth when no override is provided, while still preserving the ability to override via a real host-exported env var if ever needed. This fixes the root cause (double-default-layer conflict), not the symptom (it does not touch AuthService, the login page, or the redirect logic, none of which are broken)."
  blind_spots: "Have not exercised the actual docker-compose stack locally to directly confirm the empty-string user gets created (relying on documented Spring placeholder semantics + docker-compose `${VAR:-}` substitution semantics, both well-established behaviors, plus the DOM/network evidence which is fully consistent with this theory and inconsistent with every alternative hypothesis considered: UI redirect race, rate limiting, cross-project beforeAll race, BCrypt encoder mismatch — none of these produce the observed 401 with that exact backend-canned message on a freshly-seeded run). TEST_TMDB_KEY has the same `${VAR:-}` pattern but its Spring-side default is ALSO empty (`test.tmdb.key=${TEST_TMDB_KEY:}`), so no divergence occurs there — left unchanged as out of scope for this bug (separate, pre-existing, non-blocking issue: OMDB/TMDB key not seeded when the `TEST_TMDB_KEY` GH secret isn't configured, which only affects the 'add film' step further down the happy path, never reached in these failures)."
  candidate_causes:
    - "config: docker-compose.yml TEST_USER_EMAIL/TEST_USER_PASSWORD passthrough uses empty-string default instead of omitting the key or passing-through the host value"
    - "config: .github/workflows/e2e-ci.yml never sets TEST_USER_EMAIL/TEST_USER_PASSWORD in the 'Start full Docker Compose stack' step, relying (incorrectly) on docker-compose.yml's inner default to reach the Spring default"
  and_gate: "no — a single config change (docker-compose.yml passthrough syntax) fully resolves it; the workflow YAML's omission is expected/fine once docker-compose.yml correctly omits-rather-than-empties the key. Both bullets above describe the same root misconfiguration from two angles, not two independent contributing conditions."

hypothesis: "CONFIRMED and CI-VERIFIED — see reasoning_checkpoint above and Evidence 2026-08-29T19:33Z"
test: "Pushed fix (eabdac9), monitored live E2E Tests CI run 33270852363 to completion"
expecting: "N/A — login fix confirmed: failure moved from line 27 (toHaveURL/401) to line 35 (poster-card visibility), a downstream, previously-unreached step"
next_action: "NONE — session closed per user decision (Option A, 2026-08-29): this session's bug (login-redirect) is resolved-and-verified via CI run 33270852363 (fix commit eabdac9). The out-of-scope TEST_TMDB_KEY secret issue is now tracked separately at .planning/todos/pending/2026-08-29-configure-test-tmdb-key-github-secret.md (owner-only fix: `gh secret set TEST_TMDB_KEY`). The separately-noted cosmetic 'Stop Docker Compose' cleanup env bug (DB_PASSWORD missing a value on teardown) was fixed independently in commit e9cac14 (.github/workflows/e2e-ci.yml cleanup step's env block now mirrors the 'Start full Docker Compose stack' step's env block). Session archived to resolved/."

## Evidence

- timestamp: 2026-08-29T20:00Z
  checked: frontend/test/e2e/happy-path.spec.ts, frontend/test/e2e/smoke.spec.ts, playwright.config.ts, .github/workflows/e2e-ci.yml
  found: playwright.config.ts uses `workers: 1` (hard-serializes ALL test execution across both projects, no concurrency possible) and 2 projects (chromium, Mobile Chrome). smoke.spec.ts (unauthenticated redirect to /login) passed in both failing runs; only happy-path.spec.ts's login step failed, identically, for both projects, in all 3 attempts (1 original + 2 retries) each.
  implication: rules out a UI-timing race between concurrent test executions (impossible with workers:1) and rules out anything project/browser-specific (fails identically on Desktop Chrome and Mobile Chrome). Points toward a backend-side, environment-wide condition.

- timestamp: 2026-08-29T20:05Z
  checked: gh run view 33266244032 --log-failed (full CI log)
  found: "Running 4 tests using 1 worker" then "2 failed / 2 passed (1.7m)" — the 2 failures are happy-path[chromium] and happy-path[Mobile Chrome]; the 2 passes are smoke[chromium] and smoke[Mobile Chrome]. TEST_TMDB_KEY env var printed blank in the step's env dump (separate, pre-existing issue — GH secret not configured — irrelevant to login).
  implication: confirms scope of the failure (login only) and surfaces the TEST_TMDB_KEY-blank observation as a distinct, non-blocking issue to note but not chase.

- timestamp: 2026-08-29T20:10Z
  checked: downloaded playwright-report artifact from run 33266244032 (`gh run download 33266244032 -n playwright-report`), read the embedded error-context.md (data/1844ccfee4f012ba1f5c796b9b83cb19163152e1.md)
  found: DOM snapshot at the moment of the toHaveURL('/') timeout shows `alert: Invalid email or password.` on the /login page, alongside the correctly-filled email/password textboxes (e2e@moviearchive.test / E2ePassword1!).
  implication: this is NOT a redirect-timing bug — the backend genuinely rejected the login with 401 "invalid credentials", and the UI correctly displayed that error and stayed on /login. The bug is upstream of the redirect logic entirely.

- timestamp: 2026-08-29T20:15Z
  checked: unzipped both trace.zip attachments (chromium retry1, Mobile Chrome retry1) from the report; inspected `1-trace.network` (page network trace) and `0-trace.network` (APIRequestContext / beforeAll trace)
  found: (a) POST /api/auth/login request body = `{"email":"e2e@moviearchive.test","password":"E2ePassword1!"}`, response = 401 with body `{"message":"Invalid email or password."}`, for BOTH chromium and Mobile Chrome, in both traces. (b) POST /api/test/setup (beforeAll) returned 200 ~600-650ms before the failing login POST, in the SAME worker/trace for each project — ruling out both a stale-request race and a cross-project interleaving race (workers:1 already made the latter structurally impossible, this confirms it operationally too).
  implication: the seed call (`/test/setup`) itself succeeds every time, immediately before the failing login — so whatever it seeds must not match what the login attempts. Points at TestSetupController's actual seeded credentials diverging from the spec's hardcoded submitted credentials.

- timestamp: 2026-08-29T20:20Z
  checked: backend/src/main/java/de/moviearchive/controller/TestSetupController.java, backend/src/main/java/de/moviearchive/auth/AuthService.java (login method), backend/src/main/resources/application-test.properties, docker-compose.yml, .github/workflows/e2e-ci.yml
  found: TestSetupController seeds the user using `@Value("${test.user.email:e2e@moviearchive.test}")` / `@Value("${test.user.password:E2ePassword1!}")`, backed by `application-test.properties`'s `test.user.email=${TEST_USER_EMAIL:e2e@moviearchive.test}` (only active when SPRING_PROFILES_ACTIVE=test). docker-compose.yml's backend.environment sets `TEST_USER_EMAIL: ${TEST_USER_EMAIL:-}` / `TEST_USER_PASSWORD: ${TEST_USER_PASSWORD:-}` (map-style: always injects the key into the container, substituting '' when unset on the host). The e2e-ci.yml workflow's "Start full Docker Compose stack" step env block sets DB_PASSWORD, JWT_SECRET, ENCRYPTION_MASTER_KEY, SPRING_PROFILES_ACTIVE, TEST_TMDB_KEY — but NOT TEST_USER_EMAIL/TEST_USER_PASSWORD.
  implication: TEST_USER_EMAIL/TEST_USER_PASSWORD reach the backend container as env vars PRESENT with value '' (empty string), not absent. Spring's `${KEY:default}` placeholder syntax only falls back to `default` when the property is entirely absent from the Environment — an empty-string value counts as present, so both application-test.properties' `test.user.email` property AND the downstream `@Value` in TestSetupController resolve to '' instead of 'e2e@moviearchive.test'. AuthService.login()'s `userRepository.findByEmail("e2e@moviearchive.test")` therefore finds no user (the seeded row has email=''), throwing BadCredentialsException → 401 "Invalid email or password." — exactly matching all observed evidence.

- timestamp: 2026-08-29T20:25Z
  checked: `git log --oneline -3 -- docker-compose.yml` and `git log -p --follow -- docker-compose.yml | grep -B5 -A2 TEST_USER_EMAIL`
  found: TEST_USER_EMAIL/PASSWORD/TMDB_KEY lines were added to docker-compose.yml in commit 7c4ab03 ("fix(e2e): make Playwright happy-path tests pass reliably") during local v1.1 development — using the `${VAR:-}` empty-default pattern from the start.
  implication: confirms "why not caught" — this bug has existed since the line was written, but local dev never ran the actual dockerized `--profile app` stack against a live GH Actions environment (SPRING_PROFILES_ACTIVE=test path was presumably exercised via a different mechanism locally, or not at all) until this week's first real push to origin/main. No CI job had ever run this workflow file end-to-end before.

- timestamp: 2026-08-29T20:30Z
  checked: `gh run view 33270109258` (a second, independent CI run triggered by a later push) and its `--log-failed` output
  found: identical failure — same 401/"Invalid email or password" pattern, both projects, all retries, 2 failed / 2 passed, same as the original run.
  implication: 100% reproduction rate across 2 independent CI runs confirms this is a deterministic Bohrbug (not a genuine flake) — the debug session's "flake" framing was a premature label applied before the root cause was known; the fix should make the test pass every time, not just "more often".

- timestamp: 2026-08-29T19:33Z
  checked: pushed fix commit eabdac9 (list-style TEST_USER_EMAIL/TEST_USER_PASSWORD passthrough in docker-compose.yml) to origin/main; polled `gh run view 33270852363` (the E2E Tests workflow run triggered by this exact push, head SHA eabdac9) until completion (~7 min); read `gh run view 33270852363 --log-failed` in full.
  found: "The login-redirect symptom is GONE. Both [chromium] and [Mobile Chrome] now fail at frontend/test/e2e/happy-path.spec.ts:35 (`await expect(page.locator('[data-testid=\"poster-card\"]').first()).toBeVisible({ timeout: 15_000 })`) — a completely different line, locator, and assertion than the original bug (line 27, `toHaveURL('/')`, 'Invalid email or password' alert). The test now progresses PAST login (line ~33, `page.click('button[type=\"submit\"]')`) into the 'add film' step (TMDB search results / poster cards), which it never reached in either prior failing run. The overall job still shows 'failure' / '2 failed, 2 passed (1.8m)', but for this new, downstream reason. Separately, the always-run 'Stop Docker Compose' cleanup step also errored (`docker compose --profile app down -v` → 'required variable DB_PASSWORD is missing a value') because that step's `env:` block in e2e-ci.yml never sets DB_PASSWORD/JWT_SECRET/etc. (only the 'Start' and 'Run tests' steps do) — a separate, pre-existing, cosmetic issue (job already failed by that point; does not affect verification of the login fix)."
  implication: "This is direct, strong evidence that the docker-compose.yml fix (commit eabdac9) correctly resolves the login-redirect bug this session was opened to investigate — root cause confirmed fixed via real CI, the strongest verification signal available, exactly matching the human-verify checkpoint's own falsification criteria (login now succeeds where it previously always failed with a 401). The job's remaining failure is a DIFFERENT, already-flagged, out-of-scope issue (see reasoning_checkpoint.blind_spots and Resolution.fix's note about TEST_TMDB_KEY): `gh secret list` confirms NO repository secrets are configured at all, so `${{ secrets.TEST_TMDB_KEY }}` resolves to '' in the e2e-ci.yml workflow env, which docker-compose.yml passes through as an empty (but present) TEST_TMDB_KEY to the backend container, and `test.tmdb.key=${TEST_TMDB_KEY:}` in application-test.properties has no non-empty fallback (unlike test.user.email/password) — so the 'add film' step's real TMDB search genuinely has no API key and returns no results, timing out on the poster-card locator. This was previously non-blocking (never reached, login always failed first) and is now the new bottleneck — a distinct, separately-scoped bug requiring a real TMDB API key value that only the user can supply (cannot be fixed by further code/config changes alone)."

## Eliminated

- hypothesis: "Post-login redirect race — navigateTo('/') fires before the access_token cookie is readable by the auth middleware (client-side hydration timing)"
  evidence: "The DOM snapshot at the moment of the toHaveURL timeout shows the /login page with an 'Invalid email or password.' alert visible — the login POST itself returned 401, so no redirect was ever attempted by the frontend (handleSubmit's catch block short-circuits on non-2xx before any setAuth/navigateTo call). A redirect-timing bug would show a successful login response with the browser still stuck at /login with NO error banner, not this."
  timestamp: 2026-08-29T20:15Z

- hypothesis: "Cross-project beforeAll race — Mobile Chrome's beforeAll (POST /test/setup, which deletes+recreates the shared test user) runs concurrently with chromium's in-flight login attempt, deleting the user out from under it"
  evidence: "playwright.config.ts sets workers: 1, hard-serializing ALL test execution (no concurrent workers exist to interleave). Additionally, the extracted trace network logs show test/setup completing ~600-650ms before the failing login call, within the SAME project's own trace/worker, with wall-clock timestamps for chromium (17:44:25) and Mobile Chrome (17:45:14) that are fully sequential and non-overlapping — no interleaving occurred in either observed run."
  timestamp: 2026-08-29T20:15Z

- hypothesis: "Login rate limiting (Bucket4j, 10 req/min per IP) exhausted by repeated retries, causing 429s that get misreported"
  evidence: "The captured response is a genuine 401 with body {\"message\":\"Invalid email or password.\"}, not a 429 (which would carry a Retry-After header and a different message per AuthController's rate-limit branch). Bucket capacity (10/min) is also far above the actual attempt count (max 3 login attempts per project per run)."
  timestamp: 2026-08-29T20:15Z

- hypothesis: "BCryptPasswordEncoder bean/strength mismatch between TestSetupController's encode() and AuthService's matches() causing spurious verification failure"
  evidence: "Both beans resolve to the SAME single `@Bean BCryptPasswordEncoder passwordEncoder()` in SecurityConfig (strength 12) — there is only one bean definition, autowired identically everywhere. Also, BCrypt's matches() derives the salt from the stored hash itself, so encoder 'strength' configured on the comparing instance is irrelevant to matches() correctness regardless. Superseded by the more direct evidence that the submitted email itself doesn't match any seeded row (BadCredentialsException fires at `findByEmail(...).orElseThrow(...)`, before the password check is ever reached, per AuthService.login's ordering)."
  timestamp: 2026-08-29T20:22Z

## Resolution

- root_cause: "docker-compose.yml passes TEST_USER_EMAIL/TEST_USER_PASSWORD to the backend container using map-style `KEY: ${KEY:-}` substitution, which forces the container env var to be present-but-empty whenever the invoking shell (the GH Actions 'Start full Docker Compose stack' step) doesn't set it — which it never does. Spring's `${KEY:default}` placeholder resolution only applies the default when the property is entirely absent, not when it resolves to an empty string, so application-test.properties' `test.user.email=${TEST_USER_EMAIL:e2e@moviearchive.test}` (and the equivalent for password) resolves to '' instead of the intended default. TestSetupController therefore seeds the E2E test user with email='' / password=bcrypt(''), while the Playwright spec (correctly, via its own JS `??` fallback since its env vars are truly unset/undefined, not empty) submits 'e2e@moviearchive.test'/'E2ePassword1!' — a user lookup miss — causing AuthService.login() to throw BadCredentialsException → 401 'Invalid email or password.' on every login attempt, deterministically, in every CI run."
  fix: "Changed docker-compose.yml backend.environment entries for TEST_USER_EMAIL and TEST_USER_PASSWORD from map-style `KEY: ${KEY:-}` (always-present, empty-string default) to list-style passthrough `- KEY` (Compose only injects the container env var when the host shell has it set; omits the key entirely — not empty — when unset). This removes the conflicting empty-string default layer, letting application-test.properties' own defaults ('e2e@moviearchive.test'/'E2ePassword1!') be the single source of truth whenever no override is supplied, while still allowing a real override via a host-exported env var."
  verification: |
    target_test: { result: skipped, reason: "no existing test suite exercises docker-compose.yml resolution; the real target test is the live E2E Tests GitHub Actions workflow itself, which cannot be triggered without pushing — deferred to the human-verify checkpoint (push + monitor next CI run)" }
    mutation_check: { result: skipped, reason: "no Stryker/mutation tooling configured for YAML/infra config; not applicable to this file type" }
    no_op_deletion: { result: pass, deletion_justified_by_rca: "n/a — diff is not deletion-only; it changes two lines' resolution semantics (map-style empty-default -> no-value passthrough) and adds an explanatory comment; nothing removed" }
    adjacent_tests: { result: skipped, reason: "no test suite touches docker-compose.yml's import graph (it is not source code); TEST_TMDB_KEY line and all other backend.environment entries left untouched, confirmed via git diff" }
    revert_and_reconfirm: { result: pass, bug_returned_on_revert: true, fixed_on_reapply: true, method: "git stash push -- docker-compose.yml (revert) -> `env -i PATH=$PATH HOME=$HOME DB_PASSWORD=x JWT_SECRET=x ENCRYPTION_MASTER_KEY=x SPRING_PROFILES_ACTIVE=test docker compose --env-file /dev/null --profile app config` (simulates the CI environment: clean shell, no local .env file) -> reverted version resolved TEST_USER_EMAIL/TEST_USER_PASSWORD to \"\" (empty string, reproducing the exact bug mechanism) -> git stash pop (reapply fix) -> same command resolved both to null (omitted from container env entirely, confirming the fix)" }
    guardrail_verdict: accepted
    note: "Reduced to signals 3+5 per the 'no test suite at all' degradation path (docker-compose.yml has no existing test harness). Both available signals pass. Full end-to-end confirmation (live login succeeding in the actual dockerized stack) is deferred to the human-verify checkpoint since it requires pushing to trigger the real E2E Tests CI job — the same job that produced runs 33266244032 and 33270109258."
    live_ci_confirmation: { result: pass, run_id: 33270852363, head_sha: eabdac9, method: "pushed fix to origin/main, polled the triggered E2E Tests workflow run to completion, read full --log-failed output", found: "login-redirect symptom (toHaveURL('/') timeout at line 27, 401 'Invalid email or password') is GONE in both [chromium] and [Mobile Chrome] projects — login now succeeds and the test progresses into the downstream 'add film' step (line 35, poster-card visibility), which it never reached in either prior failing run (33266244032, 33270109258). This is the strongest available confirmation: the exact originally-reported symptom no longer occurs.", caveat: "The job's overall conclusion is still 'failure', but for a DIFFERENT, unrelated, out-of-scope reason: no GitHub repository secrets are configured at all (confirmed via `gh secret list` — empty), so the TEST_TMDB_KEY GH secret used by the 'add film' step's real TMDB search is empty, causing that step (not the login step this session covers) to time out. This is a distinct pre-existing issue already flagged as a blind spot before the fix was applied (see reasoning_checkpoint.blind_spots), now surfaced because the login bug that used to mask it is fixed. Requires a real TMDB API key supplied by the user as a GH secret — outside this session's scope and outside this agent's ability to fabricate or obtain." }
  files_changed:
    - docker-compose.yml

  closure_note: |
    User decision (2026-08-29, Option A): close this session as resolved-and-verified. The
    login-redirect bug (this session's actual root cause) is fixed and CI-confirmed (run
    33270852363, fix commit eabdac9) — login now succeeds and the test progresses past the
    redirect into the downstream "add film" step. The out-of-scope TEST_TMDB_KEY missing-secret
    issue (blocks the "add film" TMDB search from returning results) is now tracked as a
    dedicated pending todo: .planning/todos/pending/2026-08-29-configure-test-tmdb-key-github-secret.md
    — owner-only fix (`gh secret set TEST_TMDB_KEY`), not chased further in this session.

    Additionally, the separate cosmetic "Stop Docker Compose" cleanup bug noted in the
    2026-08-29T19:33Z evidence entry (cleanup step's env block missing DB_PASSWORD/JWT_SECRET/
    ENCRYPTION_MASTER_KEY/SPRING_PROFILES_ACTIVE/TEST_TMDB_KEY, causing "DB_PASSWORD is missing a
    value" warnings on `docker compose down`) was fixed independently in this closure: commit
    e9cac14 aligns the cleanup step's env block with the "Start full Docker Compose stack" step's
    env block in .github/workflows/e2e-ci.yml.

    Not waiting for a fully green CI run — the TMDB-key failure is explicitly out of scope for
    this session per the user's decision.
  related_fixes:
    - file: .github/workflows/e2e-ci.yml
      commit: e9cac14
      description: "Stop Docker Compose cleanup step env block was missing DB_PASSWORD and other vars the 'Start' step's env block sets; aligned both blocks. Independent, cosmetic fix — not part of this session's root cause."
