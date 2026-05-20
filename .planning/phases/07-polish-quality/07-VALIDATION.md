---
phase: 7
slug: polish-quality
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-20
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Playwright 1.60.0 |
| **Config file** | `frontend/playwright.config.ts` |
| **Quick run command** | `cd frontend && pnpm test:e2e --project=chromium --grep "Happy path"` |
| **Full suite command** | `cd frontend && pnpm test:e2e` |
| **Estimated runtime** | ~60 seconds (full suite, both projects) |

---

## Sampling Rate

- **After every task commit:** Run `cd frontend && pnpm test:e2e --project=chromium --grep "Happy path"`
- **After every plan wave:** Run `cd frontend && pnpm test:e2e`
- **Before `/gsd-verify-work`:** Full suite must be green (both Desktop Chromium + Mobile Chrome projects)
- **Max feedback latency:** ~60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 7-01-01 | 01 | 0 | QLTY-02 | — | N/A | E2E setup | Wave 0 — create `frontend/test/e2e/happy-path.spec.ts` | ❌ W0 | ⬜ pending |
| 7-01-02 | 01 | 0 | QLTY-02 | T-7-01 | `TestSetupController` only active when `SPRING_PROFILES_ACTIVE=test` | backend setup | Wave 0 — create `TestSetupController.java` + `application-test.properties` | ❌ W0 | ⬜ pending |
| 7-01-03 | 01 | 1 | QLTY-02 | — | N/A | E2E | `cd frontend && pnpm test:e2e --project=chromium --grep "Happy path"` | ✅ W1 | ⬜ pending |
| 7-02-01 | 02 | 1 | QLTY-01 | — | N/A | E2E | `cd frontend && pnpm test:e2e --project="Mobile Chrome"` | ✅ W1 | ⬜ pending |
| 7-03-01 | 03 | 2 | QLTY-03 | — | N/A | Manual | N/A — README review | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `frontend/test/e2e/happy-path.spec.ts` — E2E spec covering QLTY-01 (Mobile Chrome) + QLTY-02 (Desktop Chromium)
- [ ] `backend/src/main/java/de/moviearchive/controller/TestSetupController.java` — test seed endpoint, gated by `@Profile("test")`
- [ ] `backend/src/main/resources/application-test.properties` — test profile config (activates test beans)
- [ ] `data-testid` attributes on: film search result cards (`/add`), save status indicator, search result cards (`/search`), detail page title
- [ ] CI `.github/workflows/*.yml` — add `SPRING_PROFILES_ACTIVE: test` to backend service environment

*Existing infrastructure covers test runner — `playwright.config.ts` + `pnpm test:e2e` already wired.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| README is clear enough to run project from scratch | QLTY-03 | Subjective clarity judgment | Follow README steps on clean machine; verify all ENV vars listed and all ports documented |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
