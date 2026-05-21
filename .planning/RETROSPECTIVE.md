# Retrospective

## Milestone: v1.0 MVP

**Shipped:** 2026-05-21
**Phases:** 0–7 | **Plans:** 28 | **Timeline:** 8 days

### What Was Built

1. JWT auth stack — registration, email verification, login/logout, refresh token rotation, password reset
2. AES-256-GCM API key management (TMDB + OMDB) with account management settings
3. Async film enrichment pipeline — TMDB → OMDB (graceful) → Wikipedia (6-step fallback) → Postgres → OpenSearch
4. OpenSearch per-user index with custom analyzer + 40-field mapping
5. Full-text + advanced faceted search with dashboard, sorting, click-through navigation
6. Cinematic film detail page with personal fields, YouTube trailer embed, delete flow
7. Playwright E2E happy-path tests (Desktop + Mobile Chrome) + GitHub Actions CI + README

### What Worked

- **Wave 0 scaffolding pattern:** Writing @Disabled test stubs in plan -01 before production code forces test contracts to be explicit before any implementation. Never had a phase where tests didn't pass at completion.
- **Yolo mode + GSD structure:** Fast execution without confirmation gates, but structured planning artifacts (PLAN.md, VERIFICATION.md) kept quality high.
- **WireMock + MSW fixture strategy:** All external APIs always mocked — TMDB, OMDB, Wikipedia never called in CI. Zero flakiness from external API issues.
- **Testcontainers singleton pattern:** Static `@BeforeAll` container startup shared across all test classes prevents slow per-class container restarts.
- **202 Accepted UX pattern:** Returning immediately from save and polling status eliminated all timeout issues; user experience never blocked on slow external APIs.

### What Was Inefficient

- **Requirements.md traceability not maintained during execution:** Traceability table stayed "Pending" throughout phases 2–7. PROJECT.md was the live truth but REQUIREMENTS.md diverged. Fixed in archive but would be better to update during transitions.
- **Container query attempt for star rating:** Tried CSS container queries which failed due to flex shrink-to-fit sizing — needed a viewport breakpoint approach from the start. One round of rework.
- **Dark theme misunderstanding on detail page:** Applied full dark theme when only top overscroll color was requested — caused a reversal round. Clarify scope before implementing visual changes.
- **Jira ticket batching:** Tickets were closed in multiple batches across sessions rather than at phase-completion time. Per-ticket close immediately after completion (as in CLAUDE.md) would have avoided the cleanup session.

### Patterns Established

- **E2E polling pattern:** `Refresh.WaitFor` on OpenSearch index call + frontend polling until `indexedAt !== null` (not just `status === 'SUCCESS'`) ensures E2E tests don't race against async indexing.
- **Per-user OpenSearch index:** `movies-{userId}` pattern gives data isolation at infra level and trivially extends to multi-user.
- **useHead scoped styles:** `style: [{ innerHTML: '...' }]` in `useHead` injects page-scoped styles that are cleaned up on navigation — right pattern for detail-page-only overscroll color.
- **`auth.getName()` returns email:** In this project, `UserDetailsServiceImpl` uses email as username. All ownership checks must use `userRepository.findByEmail(auth.getName()).getId()`, not parse JWT subject.

### Key Lessons

1. Verify the full scope of a visual change request before implementing ("which elements should change?") — prevents revert rounds.
2. The `withJson()` pattern for OpenSearch index creation (vs. typed builder) is the correct approach with opensearch-java 2.x — document this clearly for future OpenSearch work.
3. For E2E tests involving async pipelines: both the backend write visibility (Refresh.WaitFor) AND the frontend polling condition (indexedAt not null) must be correct together. Either fix alone is insufficient.
4. Container queries report content width on flex shrink-to-fit containers, not available width — use viewport breakpoints for responsive component layout.

### Cost Observations

- Sessions: ~15 sessions across 8 days
- Notable: Yolo mode with structured GSD plans was highly efficient — most plans executed in a single session without clarification loops.

---

## Cross-Milestone Trends

| Metric | v1.0 |
|--------|------|
| Days to ship | 8 |
| Phases | 8 (0–7) |
| Plans | 28 |
| Commits | 258 |
| Files | 369 |
| Lines | ~62k |
| Test strategy | WireMock + Testcontainers + MSW + Playwright |
| Rework rounds | 3 (gap closure plan 03-06, star rating, overscroll) |
