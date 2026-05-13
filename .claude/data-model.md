# Data Model

## PostgreSQL Tables

| Table | Key Fields | Notes |
|---|---|---|
| `users` | `id` (UUID), `email`, `password_hash`, `status` (PENDING_VERIFICATION / ACTIVE / DISABLED), `created_at` | Email unique |
| `email_verification_tokens` | `id`, `user_id` FK, `token_hash`, `expires_at` (24h), `consumed_at` | Single-use |
| `password_reset_tokens` | `id`, `user_id` FK, `token_hash`, `expires_at` (1h), `consumed_at` | Single-use |
| `email_change_tokens` | `id`, `user_id` FK, `new_email`, `token_hash`, `expires_at` (24h), `consumed_at` | Single-use |
| `user_api_keys` | `id`, `user_id` FK, `provider` (TMDB / OMDB), `encrypted_key` | AES-256-GCM; OMDB optional |
| `refresh_tokens` | `id`, `user_id` FK, `token_hash`, `expires_at`, `revoked` | Rotation on refresh |
| `movies` | `id` (UUID), `user_id` FK, `tmdb_id`, `imdb_id`, `title`, `original_title`, `release_date`, `runtime`, `raw_tmdb_json` (JSONB), `raw_omdb_json` (JSONB, nullable), `wiki_plot`, `wiki_summary`, `wiki_critics`, `wiki_url`, `indexed_at` | `indexed_at = null` → not yet indexed; `raw_omdb_json = null` → no OMDB key or no match |

All token tables store SHA-256 hashes only.

---

## OpenSearch Index

**Strategy:** one index per user → `movies-{userId}`  
Rationale: data isolation at infrastructure level; trivially extendable to `movies-{userId}-{indexId}` in Phase 2.

### Custom Analyzer

```json
"custom_english_analyzer": {
  "type": "custom",
  "tokenizer": "standard",
  "filter": ["asciifolding", "lowercase", "elision", "stop_english", "kstem"]
}
```

Used on all `text` fields unless noted otherwise. Normalizes diacritics, removes stopwords, stems English words.

### Field Mapping

| Field | Type | Indexed | Analyzer / Sub-fields | Source | Notes |
|---|---|---|---|---|---|
| `tmdb_id` | `integer` | yes | – | TMDB | |
| `imdb_id` | `keyword` | yes | – | TMDB | e.g. `tt1375666` |
| `title` | `text` | yes | `custom_english_analyzer`; `.raw` → `keyword` | TMDB | keyword for sorting |
| `original_title` | `text` | yes | `custom_english_analyzer`; `.raw` → `keyword` | TMDB | |
| `tagline` | `text` | yes | `custom_english_analyzer` | TMDB | |
| `year` | `integer` | yes | – | OMDB / TMDB | |
| `release_date` | `date` | yes | – | TMDB | ISO 8601 |
| `runtime` | `integer` | yes | – | TMDB | minutes |
| `poster_path` | `keyword` | no | – | TMDB | not searchable |
| `backdrop_path` | `keyword` | no | – | TMDB | not searchable |
| `overview` | `text` | yes | `custom_english_analyzer` | TMDB | |
| `vote_average` | `float` | yes | – | TMDB | 0–10 |
| `vote_count` | `integer` | yes | – | TMDB | |
| `imdb_rating` | `float` | yes | – | OMDB | null if no OMDB |
| `imdb_votes` | `integer` | yes | – | OMDB | null if no OMDB |
| `content_rating` | `keyword` | yes | – | OMDB | e.g. `PG-13`; null if no OMDB |
| `box_office` | `integer` | no | – | OMDB | USD; null if no OMDB |
| `rating_list` | `flattened` | yes | – | OMDB | RT, Metacritic, IMDB ratings array |
| `genre_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | for aggregations |
| `director_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | OMDB / TMDB | fallback from TMDB credits |
| `writer_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | OMDB / TMDB | |
| `main_cast` | `keyword` | yes | `.text` → `text` custom_english_analyzer | OMDB | top actors string; null if no OMDB |
| `full_cast` | `nested` | yes | – | TMDB | `name`, `character`, `order`, `profile_path` |
| `full_cast_names` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | denormalized for fast name search |
| `full_crew` | `nested` | yes | – | TMDB | `name`, `job`, `department`, `profile_path` |
| `full_crew_names` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | denormalized for fast name search |
| `country_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | ISO-3166-1 codes |
| `language_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | ISO-639-1 codes |
| `company_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | production company names |
| `keyword_list` | `keyword` | yes | `.text` → `text` custom_english_analyzer | TMDB | e.g. `["time travel", "based on novel"]` |
| `imdb_link` | `keyword` | yes | – | computed | full IMDB URL |
| `poster_list` | `flattened` | no | – | TMDB | all posters with metadata |
| `backdrop_list` | `flattened` | no | – | TMDB | all backdrops with metadata |
| `video_list` | `flattened` | no | – | TMDB | trailers, teasers with YouTube keys |
| `trailer_key` | `keyword` | no | – | TMDB | primary trailer YouTube key |
| `wikipedia_url` | `keyword` | no | – | Wikipedia | |
| `wikipedia_summary` | `text` | yes | `custom_english_analyzer` | Wikipedia | intro paragraph |
| `wikipedia_plot` | `text` | yes | `custom_english_analyzer` | Wikipedia | Plot section, plaintext |
| `wikipedia_plot_html` | `text` | no | – | Wikipedia | Plot section, HTML (stored only) |
| `wikipedia_critics` | `text` | yes | `custom_english_analyzer` | Wikipedia | "Critical response" section |
| `wikipedia_full_html` | `text` | no | – | Wikipedia | full page HTML (stored only) |
| `watched` | `boolean` | yes | – | User | personal field |
| `personal_rating` | `float` | yes | – | User | 0–10 |
| `personal_notes` | `text` | yes | `custom_english_analyzer` | User | searchable |

**Note on `nested` vs `flattened`:** `full_cast` / `full_crew` use `nested` so queries like "actor X as character Y" work correctly. For simple name search use the denormalized `full_cast_names` / `full_crew_names` (much faster).

**OMDB fields** are all nullable. Null-safe queries required in search and filter logic.
