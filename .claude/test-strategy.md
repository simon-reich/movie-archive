# Test Strategy

## Principles

- Tests ship with the feature. No feature merge without tests.
- Test pyramid: many unit → fewer integration → few E2E.
- Confidence > coverage: auth flows, save-movie flow, and search ranking must be especially well tested.
- External APIs always mocked: WireMock (BE), MSW (FE). No real TMDB/OMDB/Wikipedia calls in tests.
- Mail tested against GreenMail (in-process SMTP). No real mails.
- Postgres + OpenSearch via Testcontainers. No H2.
- Test names: `shouldRejectLogin_whenEmailNotVerified`. Given-When-Then structure.

---

## Backend Test Types

| Type | Tooling | Scope |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | Service logic, mappers, encryption, JWT, token service, retry logic, OMDB/Wikipedia fallback strategy |
| Repository | Testcontainers (Postgres) + `@DataJpaTest` | Queries, constraints, JSONB (TMDB + OMDB), token hash roundtrips |
| Search | Testcontainers (OpenSearch) | Index mapping, queries, aggregations, ranking, null-safe OMDB queries |
| Web/Controller | `@WebMvcTest` + MockMvc | Routing, validation, auth protection, response shapes |
| Mail | GreenMail | Send, recipient, subject, token link in body, English templates |
| Integration | `@SpringBootTest` + Testcontainers + GreenMail | Full flows: Sign-Up→Verify→Login, Forgot→Reset, Save→OMDB→Index, Save-without-OMDB→Index |
| External API Contract | WireMock | TMDB, OMDB, Wikipedia clients against fixtures |

---

## Frontend Test Types

| Type | Tooling | Scope |
|---|---|---|
| Unit | Vitest | Composables, Pinia stores, pure functions |
| Component | Vue Test Utils + @nuxt/test-utils | Rendering, props, events, a11y basics |
| Integration | Vitest + MSW | Full pages with mocked backend |
| E2E | Playwright | User journeys against full Docker Compose stack incl. Mailpit |

---

## Coverage Targets (CI gates)

| Layer | Target |
|---|---|
| Backend service layer (JaCoCo) | ≥ 85% line + branch |
| Backend controllers | ≥ 70% |
| Backend overall | ≥ 75% |
| FE composables / stores | ≥ 80% |
| FE components | ≥ 70% |

---

## E2E Happy Paths (Playwright)

1. **Full Sign-Up + Verification**: Sign-Up → check mail in Mailpit → click verification link → login works.
2. **Forgot Password Flow**: "Forgot Password" → mail in Mailpit → reset link → new password → login.
3. **Settings**: Add TMDB key; optionally add OMDB key.
4. **Save Movie (with OMDB)**: Search "Inception" → click poster → film appears in archive with IMDB rating + content rating.
5. **Save Movie (without OMDB)**: Same without OMDB key — film saved correctly without OMDB fields.
6. **Simple Search**: Relevant results in correct order.
7. **Advanced Search**: Filter combination returns expected result set.
8. **Detail Page**: Click result → all data displayed correctly (OMDB fields if present, Wikipedia tabs, personal fields).
9. **Logout & Re-Login** with valid token refresh.

---

## Test Fixtures

| Fixture Set | Location | Contents |
|---|---|---|
| TMDB (en-US) | `backend/src/test/resources/fixtures/tmdb/` | 5–10 films incl. `imdb_id` |
| OMDB | `backend/src/test/resources/fixtures/omdb/` | 5–10 films + 1 not-found + 1 no-key scenario |
| Wikipedia (en) | `backend/src/test/resources/fixtures/wikipedia/` | Multiple films + not-found + all 6 fallback variants |
| OpenSearch seed | (generated in test setup) | 20–30 indexed films, mix with/without OMDB data |
| DB seed | `backend/src/test/resources/db/migration/` | Flyway test migrations |

---

## CI Pipeline (GitHub Actions)

**PR pipeline** (path-filtered: `backend/**` / `frontend/**`):
1. Lint + typecheck (BE + FE)
2. Backend unit + slice tests
3. Backend integration tests (Testcontainers + GreenMail)
4. Frontend unit + component tests
5. Build (BE jar + FE bundle)
6. Coverage gate (JaCoCo + Vitest)
7. (Phase 7+) E2E against Compose stack

**Main branch nightly:** full suite incl. E2E.  
**Pre-commit (local):** lint, typecheck, unit tests.

---

## Explicitly Out of Scope (MVP)

Mutation testing (PIT), contract tests (Pact), performance tests (k6), accessibility tests (axe-core) → Phase 2 backlog.
