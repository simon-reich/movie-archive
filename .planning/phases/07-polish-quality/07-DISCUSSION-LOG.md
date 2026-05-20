# Phase 7: Polish & Quality - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-20
**Phase:** 07-polish-quality
**Areas discussed:** Mobile navigation, E2E test environment, E2E auth handling, README scope

---

## Mobile Navigation

| Option | Description | Selected |
|--------|-------------|----------|
| Hamburger menu / slide-in drawer | Logo on left, hamburger icon on right. Tapping opens a full-height drawer with nav links. | ✓ |
| Bottom tab bar | Fixed bottom bar with 4–5 icon tabs. Native mobile feel. | |
| Condensed inline | Keep all links inline but icon-only on small screens. | |

**User's choice:** Hamburger menu / slide-in drawer

---

### Drawer Appearance

| Option | Description | Selected |
|--------|-------------|----------|
| Full-height overlay from the right | Drawer slides in from right, dark backdrop behind it. | |
| Full-height overlay from right — white, no backdrop | Solid warm off-white background, NO dark backdrop, no translucency, no transparency. | ✓ |
| Full-width dropdown from top | Nav expands downward below the header. | |
| You decide | Claude picks the layout. | |

**User's choice:** Full-height overlay from the right, but explicitly: NO dark backdrop, solid warm off-white background, no translucency whatsoever. Editorial white drawer.

---

## E2E Test Environment

| Option | Description | Selected |
|--------|-------------|----------|
| Full Docker stack — real backend + DB + OpenSearch | Tests target docker compose up. Real HTTP calls, real data. High-fidelity. | ✓ |
| Nuxt only + MSW mock backend | Only Nuxt dev server runs. Backend calls intercepted by MSW. | |
| Nuxt + real backend + testcontainers | Extend Spring testcontainers pattern for E2E. | |

**User's choice:** Full Docker stack

---

### CI Integration

| Option | Description | Selected |
|--------|-------------|----------|
| Both local and CI | GitHub Actions job runs docker compose up then Playwright. | ✓ |
| Local only | No CI job. | |
| You decide | Claude picks based on existing CI. | |

**User's choice:** Both local and CI

---

## E2E Auth Handling

| Option | Description | Selected |
|--------|-------------|----------|
| API-based setup — seed user via backend test endpoint | Test-only endpoint creates verified user + TMDB key in DB. Disabled in prod via ENV flag. | ✓ |
| Full sign-up + email verification via Mailpit API | Tests register user, read Mailpit, extract token, confirm. Fully realistic but slower. | |
| Cookie injection — set JWT directly | Dev-only endpoint returns signed JWT for test user. Playwright sets cookie. | |

**User's choice:** API-based setup via backend test endpoint

---

### TMDB Key Pre-seeding

| Option | Description | Selected |
|--------|-------------|----------|
| Pre-seed via the same setup endpoint | Setup endpoint creates user AND inserts TMDB key (from TEST_TMDB_KEY env). | ✓ |
| Configure via env only | TMDB key must already exist in DB from manual setup. | |
| Mock TMDB in E2E too | WireMock server intercepts TMDB calls in Playwright. | |

**User's choice:** Pre-seed via the same setup endpoint

---

## README Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Developer setup guide + feature overview | Full local setup, features section, tech stack table. | |
| Minimal — keep what's there and expand slightly | Add missing ENV vars and E2E test note. Keep it short. | ✓ |
| Full developer reference | Architecture, API overview, test strategy, contributing guide. | |

**User's choice:** Minimal — keep what's there, add missing ENV vars and E2E instructions only.

---

## Claude's Discretion

- Exact drawer width and close button placement
- Hamburger/X icon implementation (lucide-vue-next)
- Detail page mobile stacking order
- Search page filter panel mobile behavior
- E2E test file structure within `test/e2e/`
- Spring Boot test endpoint path and ENV flag name

## Deferred Ideas

- Average personal rating + watched count on dashboard (post-Phase 6 data now available)
- Dark mode
- PWA / offline mode
