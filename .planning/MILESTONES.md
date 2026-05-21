# Milestones

## v1.0 MVP — Shipped 2026-05-21

**Phases:** 0–7 (28 plans)
**Timeline:** 2026-05-13 → 2026-05-21 (8 days)
**Commits:** 258 | **Files:** 369 | **Lines:** 61,839 insertions
**Stack:** Spring Boot 3 + Java 25 / Nuxt 3 + Vue 3 + TypeScript / PostgreSQL 16 / OpenSearch 2.x

### What Shipped

1. **JWT auth stack** — registration, email verification, login/logout, 7-day refresh token rotation, password reset with enumeration protection
2. **AES-256-GCM API key management** — TMDB (required) and OMDB (optional) keys encrypted at rest; account password + email change
3. **Async film enrichment pipeline** — TMDB → OMDB (graceful degradation) → Wikipedia (6-step fallback) → Postgres → OpenSearch; 202 Accepted UX with status polling
4. **OpenSearch per-user index** — custom analyzer (asciifolding, lowercase, elision, stop_english, kstem), 40-field mapping, admin full-reindex endpoint
5. **Full-text + advanced faceted search** — free text, genre/director/year/rating/content-rating/watched filters, sorting, click-through attribute navigation, dashboard with stats
6. **Cinematic film detail page** — TMDB + OMDB metadata, Wikipedia plot/critics, personal fields (rating, notes, watched), lazy YouTube trailer embed, delete with confirmation
7. **Playwright E2E + GitHub Actions CI** — Desktop Chrome + Mobile Chrome happy-path spec, full Docker Compose CI stack, README setup documentation; mobile-responsive app (hamburger nav, single-column reflow)

### Archive

- [v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md) — Full phase details
- [v1.0-REQUIREMENTS.md](milestones/v1.0-REQUIREMENTS.md) — All requirements with final status

---

*Next milestone: `/gsd-new-milestone` to define v1.1 or v2.0*
