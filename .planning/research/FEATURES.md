# Feature Research

**Domain:** Personal film archive / movie collection web app
**Researched:** 2026-05-15
**Confidence:** HIGH (project constraints fully documented; competitor patterns verified via Letterboxd, Trakt.tv, Moviebase, AllMovie; auth UX from industry sources)

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Email/password sign-up with verification | Standard auth gate for any personal data app | MEDIUM | Already designed: PENDING_VERIFICATION → ACTIVE flow, welcome-verify.html template |
| Login / logout | Session management baseline | LOW | JWT + HttpOnly refresh cookie with rotation already designed |
| Password reset via email | 75% of users quit broken reset flows (industry data) | MEDIUM | Already designed: forgot-password → 1h token → reset → revoke all refresh tokens |
| Save a movie to the archive | Core value — without this the app has no purpose | HIGH | Async 202-pattern: TMDB → OMDB → Wikipedia → Postgres → OpenSearch |
| Full-text search over saved movies | "Finden" half of the core value proposition | HIGH | OpenSearch with custom_english_analyzer; must work on title, overview, cast, plot |
| Watched / unwatched status per film | Every collection app since the 2000s has this | LOW | `watched` boolean field already in OpenSearch mapping |
| Personal star rating | Users arrive expecting Letterboxd-style rating | LOW | `personal_rating` float 0–10 already in schema; display as 0–5 stars with halves |
| Movie detail page | Clicking a result must open a rich info page | MEDIUM | Poster, title, year, runtime, genres, cast, overview, Wikipedia plot, ratings |
| Poster/backdrop imagery | Visual identity of a film — text-only feels broken | LOW | `poster_path`, `backdrop_path`, `poster_list` already stored from TMDB |
| TMDB API key configuration | Required to save any film; without it the app is inoperable | MEDIUM | AES-256-GCM encrypted, PUT /settings/api-keys/tmdb, always masked in response |
| Responsive layout (mobile works) | Users look up films on phone mid-conversation | MEDIUM | Tailwind + shadcn-vue; mobile-polish scheduled for Phase 7 |
| Error feedback on async save | 202 Accepted means user needs to know when save completes or fails | MEDIUM | Polling or status endpoint; silent failures are unacceptable UX |

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but expected by the project's own stated value.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Wikipedia-enriched detail pages | Critics section + plot section from Wikipedia distinguishes from TMDB-only apps | HIGH | 6-step fallback already designed; `wikipedia_plot`, `wikipedia_critics`, `wikipedia_summary` in schema |
| Advanced faceted search (genre, director, year, rating, watched, content rating) | Personal archive grows to 200+ films; free-text alone fails at scale | HIGH | OpenSearch `keyword` fields on `genre_list`, `director_list`, `content_rating`, `year`, `personal_rating`, `watched` all support aggregations |
| Personal notes (searchable) | Private, searchable context — "I watched this with dad" or "reminded me of X" | LOW | `personal_notes` text field indexed with `custom_english_analyzer`; notes appear in full-text search results |
| IMDB + Rotten Tomatoes + Metacritic ratings on detail page | Aggregating multiple rating sources in one place saves users several browser tabs | LOW | `rating_list` (flattened, from OMDB) + `imdb_rating`, `vote_average` already in schema |
| OMDB graceful degradation | Usable without IMDB data — competitors often hard-require third-party keys | MEDIUM | OMDB failure never blocks save; all OMDB fields nullable; null-safe queries required |
| Trailer embedding (YouTube) | One-click trailer on detail page without leaving the app | LOW | `trailer_key` YouTube key already stored from TMDB; embed is a frontend iframe |
| Data snapshot ownership | Saved film data never changes unless user re-saves — no silent API drift | LOW | Already a key decision: raw_tmdb_json + raw_omdb_json frozen at save time |
| Per-user OpenSearch index (`movies-{userId}`) | Data isolation at infrastructure level; scales to multi-user without rearchitecting | MEDIUM | Already designed; differentiator vs. shared-index approaches that require row-level security |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems. Explicitly out of scope.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Social sharing / follow other users | Letterboxd effect — users want to see friends' lists | Turns personal archive into a social network; doubles complexity; out of scope per PROJECT.md | Focus on personal notes; export CSV if user wants to share |
| Real-time sync with Trakt / Letterboxd | Power users track everything everywhere | External API dependencies, rate limits, conflict resolution, bidirectional sync logic — massive scope | Import-once CSV as future v2 feature |
| Automatic "now watching" detection | Trakt does this via streaming platform hooks | Requires OAuth integrations with Netflix/Disney+/etc.; each integration is a separate project | Manual logging; that's the point — intentional archiving |
| Streaming availability ("where to watch") | JustWatch does this; users often ask | Streaming rights change daily; data goes stale within hours; requires JustWatch or similar API | Provide IMDB link; user finds streaming themselves |
| Recommendation engine ("if you liked X...") | Discovery feature; feels natural next to a collection | Requires ML pipeline or collaborative filtering on user data; massive scope for a personal archive | Wikipedia "similar films" section covers informal discovery |
| CSV/JSON import from Letterboxd | Power users have existing collections | Mapping external IDs to TMDB IDs reliably is non-trivial; Wikipedia fallback logic must re-run for all imports | Supported as future v2 if demand emerges |
| Offline mode / PWA | Users want archive available without internet | Service Worker + IndexedDB sync + conflict resolution = full second project | App is online-first by design (per PROJECT.md Out of Scope) |
| Real-time collaboration / shared collections | Couples or film clubs sharing one archive | Multiplayer editing conflicts; not in the personal archive concept | Single-user-first; multi-user architecture supports separate accounts |
| Barcode / physical media scanning | DVD/Blu-ray collectors | Hardware dependency, OCR/barcode library, uncertain TMDB match quality | TMDB title search covers the use case well enough |
| Comment threads / discussion under films | Community feel | Turns archive into a forum; out of scope | Personal notes field serves the solo use case |

---

## Feature Dependencies

```
[TMDB API Key in Settings]
    └──required by──> [Save Movie Flow]
                          └──required by──> [OpenSearch Index]
                                               └──required by──> [Full-text Search]
                                               └──required by──> [Advanced Faceted Search]
                                               └──required by──> [Watched Status Filter]
                                               └──required by──> [Personal Rating Filter]

[Auth (Signup + Verify + Login)]
    └──gates──> [All authenticated features]

[Save Movie Flow]
    └──populates──> [Movie Detail Page]
    └──populates──> [Search Results]

[OMDB API Key in Settings (optional)]
    └──enhances──> [Movie Detail Page] (IMDB rating, content rating, cast string)
    └──enhances──> [Advanced Search] (content_rating filter, imdb_rating range)

[Wikipedia Fetch (automatic, no key needed)]
    └──enhances──> [Movie Detail Page] (plot, critics, summary)
    └──enhances──> [Full-text Search] (wikipedia_plot, wikipedia_critics indexed)

[Personal Rating]
    └──enhances──> [Advanced Search] (filter by personal_rating range)

[Personal Notes]
    └──enhances──> [Full-text Search] (notes are indexed, appear in results)

[Watched Status]
    └──enhances──> [Advanced Search] (filter watched=true/false)
```

### Dependency Notes

- **Save Movie Flow requires TMDB API Key:** No TMDB key in settings → `POST /movies/save` must return a clear 4xx with actionable message directing user to Settings.
- **Full-text Search requires OpenSearch Index:** Search is entirely derived from the index; Phase 4 (indexing) must complete before Phase 5 (search UI) starts.
- **Movie Detail Page requires Save Movie Flow:** The detail page displays data that only exists after a successful save+index cycle; it is not a standalone feature.
- **OMDB enrichment is optional but enhances two features:** Both search filters and detail page degrade gracefully when OMDB key is absent — null-safe queries are mandatory throughout.
- **Personal fields (rating, notes, watched) require Auth:** These are per-user data; unauthenticated access must be impossible.
- **Advanced Search enhances but does not replace Full-text Search:** Both must coexist; simple search for quick lookup, advanced for power-user filtering.

---

## MVP Definition

### Launch With (v1)

Minimum viable product — everything needed to archive and find films.

- [x] Auth: Sign-up, email verification, login, logout, password reset — gates all personal data
- [x] Settings: TMDB API key management — required to save any film
- [x] Save Movie Flow (TMDB + OMDB optional + Wikipedia 6-step fallback) — core value half 1
- [x] OpenSearch indexing with custom analyzer — prerequisite for search
- [x] Full-text search (simple: title, cast, overview, plot) — core value half 2
- [x] Watched status per film — baseline personal metadata
- [x] Personal rating (0–5 stars, half-star precision) — baseline personal metadata
- [x] Movie detail page: poster, title, year, genres, cast, overview, Wikipedia sections, ratings — makes search results actionable
- [x] Personal notes (searchable) — distinguishes archive from a watchlist
- [x] Save flow status feedback — user must know when async save completes or fails

### Add After Validation (v1.x)

Features to add once the core archive loop is working.

- [ ] Advanced faceted search (genre, director, year, rating range, content rating, watched filter) — add once users have enough films that free-text alone is insufficient
- [ ] Trailer embed on detail page — low effort, high satisfaction; add as polish
- [ ] Settings: OMDB key management — add when users ask; OMDB key increases detail page richness
- [ ] Settings: change email, change password — needed before any wider release
- [ ] E2E tests + mobile polish — Phase 7 already planned; validates all happy paths on real devices

### Future Consideration (v2+)

Features to defer until personal-archive concept is validated.

- [ ] CSV import from Letterboxd — defer; mapping complexity is high, demand unproven
- [ ] OpenSearch index rebuild endpoint — useful for data recovery; not urgent for single user
- [ ] Multi-user onboarding (invite codes or open registration) — architecture supports it; product decision deferred
- [ ] Year-in-review stats (films watched per month, total runtime, top genres) — Letterboxd does this well; fun to add after collection reaches meaningful size

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Auth (signup/verify/login/reset) | HIGH | MEDIUM | P1 |
| TMDB API key settings | HIGH | LOW | P1 |
| Save movie flow (async) | HIGH | HIGH | P1 |
| OpenSearch indexing | HIGH | HIGH | P1 |
| Full-text search (simple) | HIGH | MEDIUM | P1 |
| Movie detail page | HIGH | MEDIUM | P1 |
| Watched status | HIGH | LOW | P1 |
| Personal rating (0–5 stars) | HIGH | LOW | P1 |
| Personal notes (searchable) | MEDIUM | LOW | P1 |
| Save flow async status feedback | HIGH | MEDIUM | P1 |
| Advanced faceted search | HIGH | MEDIUM | P2 |
| OMDB API key settings | MEDIUM | LOW | P2 |
| Trailer embed | MEDIUM | LOW | P2 |
| Change email / change password | MEDIUM | MEDIUM | P2 |
| Mobile-responsive polish | HIGH | MEDIUM | P2 |
| E2E tests | HIGH | HIGH | P2 |
| CSV Letterboxd import | MEDIUM | HIGH | P3 |
| Stats / year-in-review | LOW | MEDIUM | P3 |
| Multi-user onboarding | LOW | MEDIUM | P3 |

**Priority key:**
- P1: Must have for launch — core archive loop is broken without these
- P2: Should have — quality and completeness; plan for v1.x
- P3: Nice to have — defer until core validated

---

## Competitor Feature Analysis

| Feature | Letterboxd | Trakt.tv | Moviebase | Our Approach |
|---------|------------|----------|-----------|--------------|
| Personal rating | 0.5–5 stars (half-star) | 1–10 numeric | 0–10 + emoji | 0–5 stars (half-star display over 0–10 float storage) |
| Watched status | Yes, with date | Yes, auto-tracked | Yes | Yes; date optional in v1 |
| Personal notes | Diary entry + review | Short note | Review + note | Searchable personal_notes field |
| Search | Title search only (free) | Title search | Full-text | Full-text + faceted via OpenSearch |
| Faceted filters | Genre, year, rating (premium) | Many filters | Genre, year, rating | Genre, director, year, rating, content rating, watched |
| Wikipedia content | No | No | No | Plot + critics + summary (differentiator) |
| Aggregated ratings (IMDB/RT/MC) | No | Yes (Trakt score) | Yes | Yes via OMDB rating_list |
| Trailer | Link to external | No | Yes (embedded) | YouTube embed via trailer_key |
| Social | Core feature | Core feature | Minimal | Explicitly out of scope |
| Data ownership | Letterboxd-owned | Trakt-owned | App-owned | User-owned snapshot, no external runtime dependency |
| Self-hosted option | No | No | No | Full Docker Compose stack |

---

## Sources

- Letterboxd feature documentation: https://letterboxd.com/about/faq/ (MEDIUM confidence — current as of research date)
- Moviebase vs Letterboxd comparison: https://moviebase.app/resources/moviebase-vs-letterboxd (MEDIUM confidence)
- Trakt.tv / Letterboxd comparison: https://twit.tv/posts/tech/justwatch-letterboxd-trakt-which-app-should-you-use-manage-your-watchlist (MEDIUM confidence)
- Auth UX best practices 2025: https://www.authgear.com/post/login-signup-ux-guide/ (HIGH confidence)
- Password reset flow statistics: https://supertokens.com/blog/implementing-a-forgot-password-flow (MEDIUM confidence)
- Watchlist UI patterns: https://filmgrail.com/blog/top-7-watchlist-ui-features-for-cinema-apps/ (LOW confidence — single source)
- Project data model: .claude/data-model.md (HIGH confidence — authoritative)
- Project auth flows: .claude/auth-flows.md (HIGH confidence — authoritative)
- Project constraints: .planning/PROJECT.md + CLAUDE.md (HIGH confidence — authoritative)

---

*Feature research for: personal film archive web app (MovieArchive)*
*Researched: 2026-05-15*
