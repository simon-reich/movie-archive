# Pitfalls Research

**Domain:** Personal film archive — Spring Boot 3 + Nuxt 3 + OpenSearch 2.x + PostgreSQL 16
**Researched:** 2026-05-15
**Confidence:** HIGH (pitfalls verified across multiple sources and official documentation)

---

## Critical Pitfalls

### Pitfall 1: JWT Filter Registered Twice via @Component

**What goes wrong:**
If the JWT authentication filter extends `OncePerRequestFilter` and is annotated `@Component` (or exposed as a `@Bean`), Spring Boot auto-registers it in the global servlet filter chain AND Spring Security registers it a second time via `http.addFilterBefore(...)`. The filter runs twice per request. In stateless JWT setups this means the JWT is validated and the `SecurityContextHolder` is populated, cleared, and repopulated — a silent bug that wastes cycles and creates subtle ordering issues when the filter has side effects (e.g. logging, auditing).

**Why it happens:**
Spring Boot's auto-configuration scans for `Filter` beans and registers them via `FilterRegistrationBean`. Developers assume that "add to security chain" is the only registration, not realizing the container-level registration also exists.

**How to avoid:**
Do NOT annotate the JWT filter with `@Component`. Declare it as a plain class, instantiate it in the `SecurityFilterChain` `@Bean` method, and pass it directly to `http.addFilterBefore(new JwtAuthFilter(jwtService, userDetailsService), UsernamePasswordAuthenticationFilter.class)`. If you must expose it as a `@Bean`, create a `FilterRegistrationBean<JwtAuthFilter>` with `setEnabled(false)` to suppress auto-registration.

**Warning signs:**
- Log lines from the JWT filter appear twice per request.
- Debugging shows `SecurityContextHolder` populated before the filter runs (because it already ran once).
- Integration tests pass but production shows doubled latency on authenticated endpoints.

**Phase to address:** Phase 1 (Auth — JWT filter chain setup)

---

### Pitfall 2: Refresh Token Race Condition on Concurrent Requests

**What goes wrong:**
Two tab-level requests (or the Nuxt SSR server + client hydration) fire simultaneously while the access token is expired. Both hit `POST /auth/refresh` with the same refresh token cookie. The first rotation succeeds: old token consumed, new token issued. The second request arrives milliseconds later, sees the old token already consumed (`consumed_at IS NOT NULL`), and returns 401 — logging the user out spuriously. The user did nothing wrong.

**Why it happens:**
Refresh token rotation with strict single-use enforcement (which this project requires via `consumed_at`) is correct for security but creates a window where legitimate concurrent refreshes fail. Nuxt SSR is particularly prone: the server renders a page that fires a `useFetch` for protected data, while the client simultaneously attempts its own token validation on hydration.

**How to avoid:**
Two complementary strategies:
1. **Reuse interval window:** After consuming a refresh token, store the new token pair for a short window (e.g. 30 seconds). If the same old token is presented again within the window, return the cached new token rather than a 401. Auth0 calls this the "reuse interval" pattern. Implement as an additional column `grace_until TIMESTAMPTZ` on the refresh token table.
2. **Client-side request queuing:** In the Nuxt `$fetch` plugin, when a 401 is received and a refresh is already in-flight, queue the original request and retry it after the in-flight refresh resolves. Never fire two concurrent `/auth/refresh` calls from the same client.

The `consumed_at` single-use enforcement MUST stay — the reuse interval must be server-enforced and time-bounded, never just "accept reuse."

**Warning signs:**
- Users report being logged out on page load with no session expiry.
- Auth logs show `consumed_at` already set on refresh tokens that were just created.
- Spurious 401s appear in browser network tab on first page load after an access token expires.

**Phase to address:** Phase 1 (Auth — token rotation implementation)

---

### Pitfall 3: Spring Security Stateless Config Blocking the Refresh Cookie Endpoint

**What goes wrong:**
`POST /auth/refresh` must be called without a valid Bearer token (the access token has expired — that is the whole point). If the JWT filter rejects requests with missing/invalid tokens before the endpoint is reached, the refresh endpoint is unreachable. Developers sometimes configure `permitAll()` on `/auth/**` but forget that the JWT filter still runs before `permitAll()` is evaluated — a filter chain problem, not an authorization problem. The filter sets no authentication → the endpoint is technically reachable, but if the filter throws on invalid JWT rather than skipping, it returns 401 before the controller runs.

**Why it happens:**
The JWT filter typically does: if no Authorization header → skip. But "expired token" is not the same as "no header." An expired token fails signature/expiry validation and the filter either throws or sets null authentication. If the filter does not explicitly whitelist `/auth/refresh` and `/auth/login` from processing, expired tokens on those endpoints block the flow.

**How to avoid:**
In the JWT filter, check the request URI first. If the path matches `/auth/login`, `/auth/refresh`, `/auth/signup`, `/auth/verify-email`, `/auth/forgot-password`, `/auth/reset-password`, `/auth/resend-verification` — call `filterChain.doFilter(request, response)` immediately without touching the token. Never attempt token validation on auth-bootstrapping endpoints.

**Warning signs:**
- `/auth/refresh` returns 401 when called with a valid refresh cookie but an expired access token in the header.
- Token validation exceptions appear in logs for requests to `/auth/login`.
- E2E tests pass in happy path but fail when access token has actually expired.

**Phase to address:** Phase 1 (Auth — JWT filter chain setup)

---

### Pitfall 4: CSRF Not Disabled, Blocking Stateless Cookie-Based Refresh

**What goes wrong:**
Spring Security 6 enables CSRF protection by default. The refresh token lives in an HttpOnly cookie. Because the browser automatically sends the cookie with every matching request, Spring Security CSRF protection treats `POST /auth/refresh` as a potential cross-site attack and rejects it with 403, even from legitimate same-origin requests, because CSRF tokens are not embedded in the cookie flow.

**Why it happens:**
Developers configure `SessionCreationPolicy.STATELESS` thinking this disables CSRF automatically. It does not. CSRF must be explicitly disabled with `csrf(AbstractHttpConfigurer::disable)` in the `SecurityFilterChain`.

The correct rationale: CSRF protection is needed for session-cookie auth. This project uses `SameSite=Strict` on the refresh cookie AND JWT in the Authorization header for the access token — the combination renders CSRF attacks structurally impossible. Disabling CSRF is correct here.

**How to avoid:**
In `SecurityFilterChain` configuration:
```java
http
  .csrf(AbstractHttpConfigurer::disable)
  .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
Also confirm the refresh cookie is set with `SameSite=Strict; HttpOnly; Secure` — these attributes are the actual CSRF defense.

**Warning signs:**
- `POST /auth/refresh` returns 403 from curl/Postman but 200 in tests (MockMvc disables CSRF by default).
- Browser network tab shows 403 on cookie-based refresh even with correct cookie.
- CSRF filter exception appears in logs.

**Phase to address:** Phase 1 (Auth — Spring Security configuration)

---

### Pitfall 5: OpenSearch Index Not Existing on First Movie Save

**What goes wrong:**
`POST /movies/save` (async) eventually tries to index a document into `movies-{userId}`. On a fresh account, that index has never been created. OpenSearch returns a 404 `index_not_found_exception`. The save flow fails at the indexing step. The movie row exists in Postgres (good — Postgres is source of truth) but `indexed_at` remains null and the movie is invisible in search.

**Why it happens:**
Developers create the index upfront in a `@PostConstruct` or app startup, but this runs once globally. New users created after startup have no index. Per-user indexes require on-demand creation.

**How to avoid:**
Before every index write, check if the index exists and create it if not. Wrap in a helper:
```java
boolean exists = client.indices().exists(r -> r.index(indexName)).value();
if (!exists) {
    client.indices().create(r -> r.index(indexName).mappings(buildMappings()).settings(buildSettings()));
}
```
This check-then-create is idempotent with the OpenSearch API. Alternatively, enable `action.auto_create_index: true` in OpenSearch config — but this creates indexes without the custom analyzer and mapping, defeating Phase 4's purpose. Always create the index explicitly with the correct mapping and analyzer on first use.

**Warning signs:**
- First save for a new user succeeds (202 Accepted) but movies never appear in search.
- `indexed_at` is null in the database for the first movie of any user.
- OpenSearch client logs contain `index_not_found_exception` for `movies-{userId}`.

**Phase to address:** Phase 4 (OpenSearch Indexing — index bootstrap logic)

---

### Pitfall 6: OpenSearch Custom Analyzer Applied at Index Creation Only — Not Retroactively

**What goes wrong:**
The custom analyzer (e.g. a language-specific or edge n-gram analyzer for autocomplete) is designed in Phase 4 but the index was already created in Phase 3's "index on first save" path without the analyzer settings. Existing documents are indexed with the default analyzer. When the custom analyzer is added later, only new documents use it — old documents have already been analyzed and stored with the wrong token structure. Searches behave inconsistently.

**Why it happens:**
OpenSearch (and Elasticsearch) do not retroactively re-analyze documents when the analyzer is changed. The `_update_mapping` API does not allow changing the `analyzer` parameter on existing fields. A full reindex is required.

**How to avoid:**
The index creation helper used in Phase 4 must include the complete mapping and custom analyzer from the start. Never create the index without the final mapping. If the analyzer design is not finalized in Phase 4, use a placeholder that is compatible with reindexing (OpenSearch is rebuildable — Postgres is source of truth). Document an admin endpoint `POST /admin/reindex/{userId}` that drops and rebuilds the index from Postgres data.

**Warning signs:**
- Search returns different results before and after the first schema migration.
- Autocomplete works for newly added movies but not for older ones.
- OpenSearch `_analyze` API returns different token counts for old vs. new documents.

**Phase to address:** Phase 4 (OpenSearch Indexing — mapping design must precede first use)

---

### Pitfall 7: OMDB Failure Silently Blocking the Entire Save Flow

**What goes wrong:**
The OMDB call is wrapped in a try/catch that catches specific OMDB exceptions, but the TMDB call before it extracts `imdb_id` and if `imdb_id` is null (some TMDB entries lack it), the OMDB call is attempted with a null parameter — causing a NullPointerException or invalid URL construction that is NOT caught by the OMDB exception handler. The uncaught exception propagates up the `@Async` method, aborting the entire pipeline before Postgres persistence.

**Why it happens:**
"OMDB failure never blocks the save flow" is enforced at the service layer but the guard condition (`if (omdbKeyPresent && imdbId != null)`) is implemented inconsistently. Developers check for key presence but forget to check `imdbId` nullability.

**How to avoid:**
The guard must be: `if (omdbKey != null && imdbId != null && !imdbId.isBlank())`. Additionally, wrap the entire OMDB block in a catch-all:
```java
try {
    if (omdbKey != null && imdbId != null) { omdbEnrich(movie, imdbId, omdbKey); }
} catch (Exception e) {
    log.warn("OMDB enrichment failed for tmdbId={}, skipping: {}", tmdbId, e.getMessage());
}
```
The pipeline structure should be: TMDB (required, fail if error) → extract imdbId → OMDB (optional, never fail) → Wikipedia (optional, never fail) → Postgres (required) → OpenSearch (required, already guarded).

**Warning signs:**
- Movies that lack an `imdb_id` in TMDB never appear in the database.
- NullPointerException in async task logs, but the endpoint returned 202 successfully.
- Test coverage only tests the "OMDB key missing" path, not the "OMDB key present but imdb_id absent" path.

**Phase to address:** Phase 3 (Save Movie Flow — async enrichment pipeline)

---

### Pitfall 8: Wikipedia 6-Step Fallback Making 6 Synchronous HTTP Calls Serially

**What goes wrong:**
The Wikipedia fallback tries up to 6 title variants, stopping at the first match. If the movie is obscure or the title contains special characters, all 6 calls fail. Each call goes through the `@Retryable` wrapper with exponential backoff (1s, 2s, 4s per retry × 3 attempts = up to 7s per call). 6 × 7s = 42 seconds for the Wikipedia step alone, during which the async thread is blocked, consuming a thread pool slot.

**Why it happens:**
`@Retryable` was designed for the case where the service is temporarily down. But Wikipedia's API responding "page not found" with 200 OK is not a retryable condition — it is a definitive answer. The retry wrapper must distinguish HTTP errors (retry) from "no such page" responses (do not retry).

**How to avoid:**
The Wikipedia client's fallback method must:
1. Return an empty result immediately on HTTP 200 with `"missing": true` in the response — do NOT retry.
2. Only retry on HTTP 5xx or connection timeouts.
3. Implement the 6-step loop in a single method (not 6 separate `@Retryable` methods), so each fallback step fires once and the overall method retries only on transient network failure.

Also set a sane per-call timeout (e.g. 3 seconds) in the RestClient configuration to cap the worst case.

**Warning signs:**
- Async save tasks take 20+ seconds for unknown/foreign films.
- Thread pool metrics show high utilization even under low request volume.
- Wikipedia client logs show multiple retry attempts with "page not found" type responses.

**Phase to address:** Phase 3 (Save Movie Flow — Wikipedia enrichment)

---

### Pitfall 9: Nuxt SSR Sending Bearer Token on Server Side During SSR Render

**What goes wrong:**
Nuxt renders pages server-side. On initial page load, the SSR server must call the Spring Boot API to fetch data (e.g. movie list, user info). The access token (JWT) is a short-lived Bearer token, not a cookie. The SSR server has no way to read it from the browser — it lives in memory in the Vue/Pinia store on the client only. The SSR render makes the API call without authentication, gets 401, and either crashes the render or returns an empty/unauthenticated page state.

**Why it happens:**
Access token stored in memory (JS heap) is correct for security against XSS, but memory is not shared between browser and SSR server. Developers assume `useState` or Pinia state is automatically available during SSR — it is not for tokens that were never sent from the client.

**How to avoid:**
Two-cookie strategy: the refresh token cookie (HttpOnly, 7 days) is already available to the SSR server because cookies are forwarded with every request. Add a second non-HttpOnly short-lived cookie (or use Nuxt's `useRequestHeaders`) to forward a "session hint" that lets the SSR server call `/auth/refresh` server-side to obtain a fresh access token before rendering. Alternatively, only fetch non-sensitive/public data during SSR and fetch authenticated data client-side after hydration. For this personal-use app, client-side-only data fetching (with a loading state) is acceptable and simpler.

**Warning signs:**
- Pages render correctly in dev (no SSR) but show empty state in production (SSR enabled).
- 401 errors appear in server-side Nuxt logs for API calls during page rendering.
- Pinia store is empty on first hydration despite user being logged in.

**Phase to address:** Phase 1 (Auth — Nuxt auth plugin and SSR token strategy)

---

### Pitfall 10: AES-256-GCM Nonce Reuse When Encrypting API Keys

**What goes wrong:**
API keys (TMDB, OMDB) are encrypted with AES-256-GCM before storage. If the nonce (IV) is derived from the user ID, a timestamp rounded to seconds, or any predictable value — or if it is hardcoded — two encryptions with the same key and same nonce break AES-GCM authentication entirely. An attacker with access to two ciphertexts encrypted with the same nonce can recover both plaintexts via XOR. Nonce reuse in GCM is catastrophic.

**Why it happens:**
Developers use `SecureRandom` to generate the nonce but fail to prepend the nonce to the ciphertext before storage, losing it. On decryption, a fixed IV is used instead, which happens to work for the first record but silently uses the wrong IV for all others.

**How to avoid:**
Store nonce + ciphertext together: `byte[] stored = concat(nonce, ciphertext)`. On encryption: generate a fresh 12-byte `SecureRandom` nonce for every encryption call. On decryption: extract the first 12 bytes as nonce, remainder as ciphertext. Never derive the nonce from any deterministic value. The standard pattern in Java:
```java
byte[] nonce = new byte[12];
new SecureRandom().nextBytes(nonce);
// encrypt → stored value = Base64(nonce || ciphertext || authTag)
```

**Warning signs:**
- All API key decryptions work for the first user but return garbage for subsequent users.
- Unit tests pass because they always encrypt and decrypt in the same test run (same nonce by coincidence).
- Integration tests fail with `AEADBadTagException` when decrypting keys stored across test runs.

**Phase to address:** Phase 2 (Settings — API key encryption implementation)

---

### Pitfall 11: @Async Thread Pool Exhaustion Under Retry Storms

**What goes wrong:**
Spring Boot's default `@Async` executor is `SimpleAsyncTaskExecutor`, which creates a new thread per invocation and does not reuse threads. If TMDB is slow or down, the `@Retryable` wrapper holds the async thread for up to `1s + 2s + 4s = 7s` per retry cycle while the thread pool fills with waiting enrichment tasks. Under even moderate load (10 concurrent save requests), all available threads are consumed. New saves queue indefinitely or are rejected, and the application appears to hang.

**Why it happens:**
`SimpleAsyncTaskExecutor` has no pool size limit. The `@Retryable` + `@Async` combination is specifically documented as a thread-blocking anti-pattern: the thread waits through the entire retry delay, doing nothing useful.

**How to avoid:**
Configure a `ThreadPoolTaskExecutor` explicitly:
```java
@Bean(name = "movieEnrichmentExecutor")
public TaskExecutor movieEnrichmentExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(4);
    exec.setMaxPoolSize(10);
    exec.setQueueCapacity(50);
    exec.setRejectedExecutionHandler(new CallerRunsPolicy());
    exec.initialize();
    return exec;
}
```
Use `@Async("movieEnrichmentExecutor")` on the enrichment method. Set a `RejectedExecutionHandler` that logs and persists the failure rather than silently dropping the task. Monitor queue depth.

**Warning signs:**
- Save endpoint returns 202 but movies never appear even after minutes.
- Thread count in JVM metrics climbs unboundedly under load.
- `java.util.concurrent.RejectedExecutionException` appears in logs under normal usage.

**Phase to address:** Phase 3 (Save Movie Flow — async executor configuration)

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Skip reuse-interval on refresh token rotation | Simpler token table schema | Spurious logouts on concurrent page loads, especially at SSR hydration | Never — implement from day one |
| `SimpleAsyncTaskExecutor` (Spring default) | Zero config | Thread exhaustion under retries, no backpressure | Never in production |
| Create OpenSearch index without final mapping | Faster Phase 3 delivery | Full reindex required when custom analyzer is added in Phase 4 | Never — agree on mapping before first index creation |
| Store TMDB/OMDB API key raw (not encrypted) | Simpler implementation | DB leak exposes user API keys immediately | Never — AES-256-GCM is non-negotiable per CLAUDE.md |
| Client-side-only data fetching (no SSR auth) | Avoids SSR token complexity | Loading flash on every authenticated page, worse TTI | Acceptable for v1 personal-use deployment |
| Hard-code nonce to zero for AES-GCM in tests | Predictable test behavior | Makes nonce-reuse bugs invisible in tests | Never — use SecureRandom even in tests |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| TMDB API | Using `language=en-US` only at search time, not at detail fetch (`/movie/{id}`) | Apply `language=en-US&append_to_response=credits,keywords,videos,images,release_dates,external_ids` at detail fetch too — missing `append_to_response` requires 6 separate API calls |
| OMDB API | Calling OMDB with `imdb_id` extracted from TMDB `external_ids` without checking if the field exists | Always guard: `imdbId != null && !imdbId.isBlank()` before OMDB call |
| Wikipedia API | Omitting or using a generic `User-Agent` header | Use a descriptive User-Agent with contact info: `MovieArchive/0.1 (https://github.com/simon-reich/movie-archive; contact@example.com)` — Wikimedia now auto-rate-limits generic agents |
| Wikipedia API | Retrying on 200-with-missing-page as if it were a transient error | Check `response.query.pages` — a page with `"missing": true` is definitive, not retryable |
| OpenSearch | Using Spring Data's `@Document`-based auto-index creation | Spring Data OpenSearch performs a HEAD check at startup and creates the index without custom analyzer settings; use the low-level client directly and create the index manually with full mapping |
| OpenSearch | Treating `index_not_found_exception` as a fatal error | It is expected on first save for new users; catch and create the index, then retry the write |
| Mailpit (dev) | Sending mail in tests without mocking, relying on Mailpit being up | Use GreenMail in `@SpringBootTest` so mail tests are self-contained and work in CI without Docker |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Wildcard queries in OpenSearch (`*query*`) | Search is slow, high CPU on OpenSearch nodes | Use the custom analyzer with edge n-grams for prefix matching instead of leading wildcards | Even at 100 movies per user — leading wildcards bypass the inverted index |
| N-gram min/max gram too wide | Huge index size, slow indexing | Set `min_gram=2, max_gram=10` or narrower to match actual query patterns | At ~1,000 movies per user |
| Fetching full `raw_tmdb_json` / `raw_omdb_json` JSONB in search results | Large payloads, slow Postgres queries | Only return summary fields in list endpoints; lazy-load raw JSON on detail page only | At ~500 movies |
| Synchronous TMDB search on every keystroke from Nuxt frontend | TMDB rate limit hit (50 req/sec global per key), high latency | Debounce search input ≥300ms client-side; never call TMDB on every keypress | Immediately — no scale threshold needed |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Refresh token stored as plaintext in DB | DB read → immediate session hijacking for all users | Store as SHA-256 hash only; compare hash of incoming token against stored hash |
| Access token in `localStorage` | XSS exfiltrates all access tokens | Store access token in memory (Pinia/reactive ref) only; never write to storage |
| Email enumeration on sign-up / forgot-password | Attacker can discover registered emails | `/auth/signup` returns same 200 response whether email exists or not; `/auth/forgot-password` always 200 per spec |
| Password reset without revoking all refresh tokens | Old sessions remain valid after reset | On `POST /auth/reset-password`: set new password AND revoke all `refresh_tokens` for the user |
| TMDB/OMDB keys logged in debug output | Keys visible in log aggregation systems | Mask keys in logs; audit all log statements in the API key management service |
| AES master key in application.properties | Key committed to repo accidentally | Master key from ENV variable only; never in any config file; `.gitignore` all `.env` files |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No feedback during async movie save (202 Accepted but silent) | User re-submits, creates duplicates | Poll a `GET /movies/{id}/status` endpoint or use optimistic UI with a "Saving..." indicator |
| Redirecting to login page when access token expires mid-session | Jarring interruption, loses navigation state | Silently refresh token in the background via the Nuxt fetch plugin; only redirect to login on refresh token expiry |
| Showing "no movies found" immediately after first save | Confusing — user just saved a movie | Display a "Indexing in progress" state until `indexed_at` is set |
| Broken movie detail page when Wikipedia data is null | Null reference errors in templates | Design all Wikipedia fields as nullable in the frontend; show a "No Wikipedia data available" section gracefully |

---

## "Looks Done But Isn't" Checklist

- [ ] **JWT refresh endpoint:** `/auth/refresh` returns 401 when access token is expired (not just missing) — verify the JWT filter explicitly skips validation on this endpoint
- [ ] **Refresh token rotation:** Concurrent tab scenario tested — two simultaneous refresh calls do not log the user out
- [ ] **OMDB skip path:** Test exists for: key configured + TMDB returns no `imdb_id` → movie saved without OMDB data (not failed)
- [ ] **OpenSearch first-user index:** New user signs up → saves first movie → movie appears in search (not just in Postgres)
- [ ] **Custom analyzer applied to existing data:** After analyzer change, trigger reindex from Postgres and verify old movies searchable
- [ ] **Wikipedia User-Agent:** Wikipedia calls include a compliant User-Agent with contact info — verified in WireMock test assertions
- [ ] **AES nonce storage:** Decrypt an API key from a cold-started application (nonce not in memory) — verify it succeeds
- [ ] **Email verification enforcement:** `POST /auth/login` with `PENDING_VERIFICATION` user returns 403, not 200
- [ ] **Password reset revokes sessions:** After `POST /auth/reset-password`, all existing refresh tokens return 401
- [ ] **Thread pool configured:** Async executor is `ThreadPoolTaskExecutor`, not `SimpleAsyncTaskExecutor` — verify in actuator `/actuator/metrics/executor.*`

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| JWT filter registered twice | LOW | Remove `@Component` from filter, redeploy — no data migration needed |
| Refresh token race condition causes mass logouts | LOW | Deploy reuse-interval fix, users simply log in again — no data loss |
| OpenSearch index created without custom analyzer | MEDIUM | Drop index, recreate with correct mapping, trigger reindex from Postgres via admin endpoint |
| OMDB null-guard bug — movies lost from Postgres | HIGH | Bug fix + manual review of async task failures; movies with `indexed_at = null` and no DB row require re-save by user |
| AES nonce reuse — all API keys unreadable | HIGH | If nonce was fixed/deterministic: decrypt all existing keys with the wrong nonce before deploying fix; requires a data migration script |
| Thread pool exhaustion — saves silently dropped | MEDIUM | Configure executor + deploy; movies not saved to Postgres require user re-save; movies in Postgres but not indexed are recovered by admin reindex endpoint |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| JWT filter registered twice | Phase 1 | Integration test: assert JWT filter logs appear exactly once per request |
| Refresh token race condition | Phase 1 | Test: simulate two concurrent `/auth/refresh` calls with same token; assert one succeeds, one returns cached new token (not 401) |
| Filter blocks refresh/login endpoints | Phase 1 | Test: call `/auth/refresh` with expired access token in header; assert 200 |
| CSRF blocking refresh cookie | Phase 1 | Test: `MockMvc` with `csrf().disable()` matches production; confirm explicit `csrf(AbstractHttpConfigurer::disable)` in config |
| OMDB null-guard bug | Phase 3 | Test: save flow with TMDB movie that has no `imdb_id`; assert movie persisted to Postgres and indexed |
| Wikipedia retry on definitive 404 | Phase 3 | WireMock test: Wikipedia returns 200 with missing page; assert no retry, enrichment skipped, save completes |
| Thread pool exhaustion | Phase 3 | Load test: 20 concurrent save requests with TMDB WireMock delayed 2s; assert all tasks complete, no thread starvation |
| OpenSearch index not existing on first save | Phase 4 | Test: fresh user saves first movie; assert index `movies-{userId}` created with correct mapping and document indexed |
| Custom analyzer retroactive application | Phase 4 | After mapping change: assert reindex admin endpoint rebuilds all documents with new token structure |
| Nuxt SSR token strategy | Phase 1 | E2E test with Playwright: verify authenticated page renders correctly on hard reload (SSR path), not just on client navigation |
| AES-256-GCM nonce reuse | Phase 2 | Test: encrypt same key twice; assert stored ciphertexts are different (different nonces) |

---

## Sources

- Spring Security filter double registration: https://copyprogramming.com/howto/spring-security-filter-chain-executed-twice-per-request-why
- OncePerRequestFilter and @Component pitfall: https://www.baeldung.com/spring-onceperrequestfilter
- JWT refresh token race conditions: https://dev.to/silentwatcher_95/race-conditions-in-jwt-refresh-token-rotation-3j5k
- Refresh token reuse interval pattern: https://mihai-andrei.com/blog/refresh-token-reuse-interval-and-reuse-detection/
- Auth0 refresh token security (reuse detection): https://auth0.com/blog/refresh-token-security-detecting-hijacking-and-misuse-with-auth0/
- @Async pitfalls in Spring Boot: https://serdaralkancode.medium.com/problems-and-solutions-when-using-async-in-spring-boot-e383f9d3b45d
- @Async + @Retryable thread-blocking anti-pattern: https://dzone.com/articles/how-to-create-asynchronous-and-retryable-methods-with-failover-support
- OpenSearch index_not_found_exception: https://opensearch.org/blog/error-logs/error-log-index_not_found_exception-the-missing-index/
- OpenSearch analyzer cannot be changed on existing fields: https://docs.opensearch.org/latest/analyzers/custom-analyzer/
- OpenSearch wildcard query performance: https://docs.opensearch.org/latest/query-dsl/term/wildcard/
- Nuxt 3 SSR + Pinia hydration mismatch: https://github.com/vuejs/pinia/discussions/2441
- Nuxt 3 JWT + HttpOnly cookie SSR pattern: https://www.linkedin.com/pulse/implementing-jwt-authentication-http-only-cookies-nuxt-guan-xin-wang-28inc
- AES-GCM nonce reuse attack: https://www.elttam.com/blog/key-recovery-attacks-on-gcm
- AES-GCM nonce implementation (Java): https://www.baeldung.com/java-encryption-iv
- Wikimedia API User-Agent policy: https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits
- Spring Security CSRF with stateless REST: https://www.baeldung.com/spring-security-csrf
- OpenSearch per-user index mapping: https://aws.amazon.com/blogs/big-data/matching-your-ingestion-strategy-with-your-opensearch-query-patterns/

---
*Pitfalls research for: Personal film archive (Spring Boot 3 + Nuxt 3 + OpenSearch 2.x)*
*Researched: 2026-05-15*
