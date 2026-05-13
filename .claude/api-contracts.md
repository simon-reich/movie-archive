# External API Contracts

## TMDB API

**Base URL:** `https://api.themoviedb.org/3`  
**Auth:** `?api_key={userTmdbKey}` (per-user, AES-encrypted in DB)  
**Required:** Yes — no TMDB key = no save flow  
**Rate limit:** 50 req/sec global

| Call | Endpoint | Notes |
|---|---|---|
| Search | `GET /search/movie?query={q}&language=en-US` | Returns poster grid results |
| Detail | `GET /movie/{tmdbId}?language=en-US&append_to_response=credits,keywords,videos,images,release_dates,external_ids` | Full detail fetch; `external_ids` contains `imdb_id` |

**Key fields from detail response used for indexing:**
- `id` (tmdb_id), `imdb_id` (from external_ids), `title`, `original_title`, `tagline`, `overview`, `release_date`, `runtime`, `vote_average`, `vote_count`
- `credits.cast[]` → `full_cast`, `full_cast_names`
- `credits.crew[]` → `full_crew`, `full_crew_names`, `director_list`, `writer_list`
- `genres[]` → `genre_list`
- `production_countries[]` → `country_list`
- `spoken_languages[]` → `language_list`
- `production_companies[]` → `company_list`
- `keywords.keywords[]` → `keyword_list`
- `videos.results[]` → `video_list`, `trailer_key` (first YouTube Trailer)
- `images` → `poster_list`, `backdrop_list`
- `release_dates` → `content_rating` fallback (US rating)

---

## OMDB API

**Base URL:** `https://www.omdbapi.com/`  
**Auth:** `?apikey={userOmdbKey}` (per-user, optional, AES-encrypted)  
**Required:** No — skip entirely if no key configured  
**Rate limit:** 1,000 req/day (free tier)  
**Failure behavior:** Any error (no key, no match, API error) → save film without OMDB data, never blocks flow

**Call:** `GET /?apikey={key}&i={imdbId}&plot=full`

**Fields extracted:**

| OMDB Response Field | Mapped To |
|---|---|
| `Title` | `title` (English title, overrides if different) |
| `Year` | `year` |
| `Rated` | `content_rating` |
| `Director` | `director_list` |
| `Writer` | `writer_list` |
| `Actors` | `main_cast` |
| `Ratings[]` | `rating_list` (RT, Metacritic, IMDB objects) |
| `imdbRating` | `imdb_rating` |
| `imdbVotes` | `imdb_votes` |
| `BoxOffice` | `box_office` (parsed to integer USD) |

Raw response stored as `raw_omdb_json` (JSONB) in Postgres.

---

## Wikipedia API

**Base URL:** `https://en.wikipedia.org/w/api.php`  
**Auth:** None (User-Agent header: `MovieArchive/0.1`)  
**Required:** No — no match → save film with `wiki_*` fields null

### 6-Step Title Fallback Strategy

Tried in order until a page is found:
1. `{OriginalTitle}_{Year}_film` (e.g. `Inception_(2010_film)`)
2. `{OriginalTitle}_(film)`
3. `{OriginalTitle}`
4. `{Title}_{Year}_film`
5. `{Title}_(film)`
6. `{Title}`

### Data Extracted

| Field | Content |
|---|---|
| `wikipedia_url` | Full URL to the Wikipedia page |
| `wikipedia_summary` | Intro paragraph (plaintext) |
| `wikipedia_plot` | "Plot" section (plaintext) |
| `wikipedia_plot_html` | "Plot" section (HTML, stored but not indexed) |
| `wikipedia_critics` | "Critical response" section (plaintext) |
| `wikipedia_full_html` | Entire page as HTML (stored but not indexed) |

---

## SMTP / Mail

| Environment | Config | Notes |
|---|---|---|
| Dev | Mailpit `localhost:1025` | Web UI at `localhost:8025`, no auth |
| Prod | SMTP credentials from ENV | Recommended: **Brevo** (300 mails/day free) or **Resend** |

Spring Mail config reads `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` from ENV.  
Templates: Thymeleaf, English-only, in `backend/src/main/resources/templates/mail/`.

---

## Retry Policy (all external calls)

`@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))`  
Applies to: TMDB client, OMDB client, Wikipedia client.  
On final failure: `indexed_at` stays `null`, error logged. Admin endpoint `GET /admin/movies/unindexed` can retry.
