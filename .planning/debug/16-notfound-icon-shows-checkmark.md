---
status: diagnosed
trigger: "UAT Test 3 (16-UAT.md, gap G-16-3): NOT_FOUND movies show a distinct icon in per-movie history, not the success checkmark — reported failing for 'Artists Under the Big Top: Perplexed'"
created: 2026-08-29T16:00:00Z
updated: 2026-08-29T16:40:00Z
---

## Current Focus

hypothesis: CONFIRMED — see Resolution
test: n/a — root cause confirmed via live DB query
expecting: n/a
next_action: none — goal is find_root_cause_only; hand off to plan-phase --gaps

## Symptoms

expected: A movie for which no Wikipedia data was found (NOT_FOUND outcome) shows a distinct
  neutral "not found" icon/label in the per-movie history — never the same checkmark icon used
  for a genuinely successful wiki-data fetch (D-09, 16-02-PLAN.md).
actual: The movie "Artists Under the Big Top: Perplexed" shows a checkmark (SUCCESS) icon in
  the per-movie history every time it is reprocessed, which the user reads as "as if it
  succeeded" when they believe no data was found for it.
errors: None reported.
reproduction: Trigger a wiki-reload run with `wiki.retry.cooldown-days=0` (currently disabled
  for dev testing per STATE.md quick-task 260826-pwp) that includes "Artists Under the Big Top:
  Perplexed" — it is re-selected as eligible on every run and always reports SUCCESS.
started: Discovered during live UAT of Phase 16 plan 16-02, despite
  frontend/test/unit/pages/settings.spec.ts asserting the 3-state icon rendering passes.

## Eliminated

- hypothesis: Frontend maps an unrecognized/mismatched backend enum string to a default
    "success" rendering (enum name mismatch, e.g. case sensitivity or NOT_FOUND vs NotFound).
  evidence: frontend/pages/settings.vue lines 539-541 use `entry.status === 'SUCCESS'` ->
    checkmark, `=== 'NOT_FOUND'` -> MinusCircle, `v-else` (unrecognized/FAILED) -> XCircle.
    The push-into-history fallback (settings.vue:145) defaults an absent/nullish
    `lastMovieStatus` to `'FAILED'`, not `'SUCCESS'`. A naming mismatch would therefore render
    the X icon, never the checkmark. Backend's `WikiReloadService.WikiRetryOutcome` enum values
    (SUCCESS, NOT_FOUND, FAILED) are serialized via `.name()` at
    WikiReloadService.java:222/226 and match the frontend's string comparisons exactly. Ruled
    out.
  timestamp: 2026-08-29T16:10:00Z

- hypothesis: WikiReloadProgressService.complete() re-broadcasts the prior processed movie's
    lastMovieTitle/lastMovieStatus as a terminal event (confirmed real bug, causes G-16-2's
    duplicate-row symptom), and this duplication is ALSO responsible for G-16-3's wrong-status
    display for this specific movie.
  evidence: WikiReloadProgressService.complete() (lines 182-197) does construct a terminal
    ProgressState reusing `prior.lastMovieTitle()`/`prior.lastMovieStatus()` verbatim — this is
    a genuine duplicate-emission bug (matches G-16-2's "last title repeated twice" report
    exactly) but it always reuses the SAME status value that was correctly published for that
    movie the one time it was actually processed in that iteration — it cannot flip a
    NOT_FOUND-published status to SUCCESS, since ProgressState is an immutable record built
    from the single prior publish() call. Confirmed via code reading; not the mechanism behind
    G-16-3. (This remains a real, separate bug worth flagging for G-16-2's fix, but is not
    G-16-3's root cause.)
  timestamp: 2026-08-29T16:15:00Z

- hypothesis: WikipediaClient caches/shares a Wikidata-resolved article title across movies
    with a null/blank imdbId, so a genuinely-not-found movie inherits another movie's
    successfully-found result.
  evidence: Read WikipediaClient.java in full. `resolveViaWikidataSparql`/
    `resolveChunkViaWikidataSparql` build a fresh `HashMap` per batch call from the SPARQL
    response bindings only (keyed by the queried imdbId values themselves) — no
    service-level/instance field cache exists that could leak a result across movies. Ruled
    out.
  timestamp: 2026-08-29T16:18:00Z

- hypothesis: The Wikipedia search-API fallback (`tryFetchViaSearch`) matched an unrelated,
    wrong Wikipedia page for this movie (false-positive match with no topic-relevance check),
    producing a spurious SUCCESS.
  evidence: Live DB query (see Evidence) shows `wiki_url =
    https://en.wikipedia.org/wiki/Artists_Under_the_Big_Top:_Perplexed` and `wiki_summary`
    begins "Artists in the Big Top: Perplexed () is a 1968 West German film written and
    directed by Alexander Kluge...— the story of a failing circus..." — this is the correct,
    genuine Wikipedia article for this exact film (IMDb tt0062679, matches the reported
    original title "Die Artisten in der Zirkuskuppel: Ratlos"). Not a mismatch. Ruled out.
  timestamp: 2026-08-29T16:25:00Z

## Evidence

- timestamp: 2026-08-29T16:05:00Z
  checked: backend/src/main/java/de/moviearchive/movie/MovieRepository.java,
    findEligibleForWikiReload query (lines 62-78)
  found: |
    @Query("SELECT m FROM Movie m WHERE m.user.id = :userId "
           + "AND m.wikiPlot IS NULL AND m.wikiCritics IS NULL "
           + "AND m.status = de.moviearchive.movie.MovieStatus.SUCCESS "
           + "AND (m.wikiLastAttemptedAt IS NULL OR m.wikiLastAttemptedAt < :cutoff)")
    Eligibility is keyed on `wiki_plot IS NULL AND wiki_critics IS NULL` — NOT on
    `wiki_url IS NULL`. The adjacent javadoc (lines 62-72) confirms this is deliberate: an
    earlier fix intentionally treats "has a Wikipedia page but the Plot/Critical-response
    sections are missing" the same as "fully missing" for RETRY-ELIGIBILITY purposes, because
    the movie detail page's own visibility guard (`v-if="movie.wikipediaPlot ||
    movie.wikipediaCritics"`) does the same collapse.
  implication: A movie whose real Wikipedia page has already been found and linked
    (`wiki_url` set) but whose article structure never yields a "Plot" or "Critical
    response"/"Reception"/"Critical reception" section stays permanently eligible for
    wiki-reload, since re-fetching the identical, unchanged real article will deterministically
    reproduce the exact same (empty) plot/critics result forever.

- timestamp: 2026-08-29T16:08:00Z
  checked: backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java,
    doRetryWikipedia() (lines 105-145) and WikiRetryOutcome enum (line 55)
  found: WikiRetryOutcome.SUCCESS is returned purely based on whether
    `wikipediaClient.fetch(...)` returns a result without throwing
    `WikipediaNotFoundException` — i.e., "a Wikipedia page/article was located at all." It has
    no dependency on whether `wiki.plot()`/`wiki.critics()` (the extracted section content) are
    non-null. `status = outcome.name()` is published to the frontend unmodified
    (WikiReloadService.java:222, 241).
  implication: The per-movie history's SUCCESS/NOT_FOUND/FAILED classification (D-09) uses a
    LOOSER definition of "found" (page exists) than the retry-eligibility query's definition
    (page has extractable Plot or Critics content). These are two independently-implemented,
    conflicting definitions of "the Wikipedia data was found for this movie" living in the same
    feature.

- timestamp: 2026-08-29T16:30:00Z
  checked: Live dev Postgres DB (docker exec movie-archive-postgres-1 psql), movies table row
    for "Artists Under the Big Top: Perplexed" (id be6fec2a-8480-414f-bef9-852ce30820d6)
  found: |
    status=SUCCESS, wiki_url=https://en.wikipedia.org/wiki/Artists_Under_the_Big_Top:_Perplexed
    (a real, correctly-matched article — confirmed by wiki_summary content: "Artists in the Big
    Top: Perplexed () is a 1968 West German film written and directed by Alexander Kluge...");
    wiki_plot IS NULL; wiki_critics IS NULL; wiki_summary IS NOT NULL;
    wiki_last_attempted_at=2026-08-29 13:45:02 (very recent — consistent with being
    re-processed on every run since cooldown-days=0 for dev testing per STATE.md quick-task
    260826-pwp).
  implication: This is direct, conclusive proof the backend's real, current classification for
    this movie's last attempt genuinely IS `WikiRetryOutcome.SUCCESS` (a Wikipedia page was
    found and linked) — not a misclassification, not stale/duplicated data, not a wrong-page
    match. The checkmark the frontend renders is accurate to what the backend actually
    computed and sent. The UAT test's own premise ("NOT_FOUND outcome, e.g. 'Artists Under the
    Big Top: Perplexed'") is factually incorrect for this specific movie — its true, current
    per-run outcome is SUCCESS, not NOT_FOUND.

- timestamp: 2026-08-29T16:33:00Z
  checked: Live dev Postgres DB — how many movies match the same "page found, no
    plot/critics content" shape
  found: |
    SELECT (wiki_url IS NOT NULL) AS has_wiki_page, count(*) FROM movies WHERE status='SUCCESS'
    AND wiki_plot IS NULL AND wiki_critics IS NULL GROUP BY 1;
     has_wiki_page | count
    ---------------+-------
     f             |   264
     t             |    41
  implication: This is not a one-off data anomaly. 41 movies in the live dataset already have
    a linked Wikipedia page yet remain permanently "eligible for reload" under the current
    query and will report SUCCESS with a checkmark on every future run while never being able
    to leave the eligible pool (their real article will never grow a Plot/Critical-response
    section just because it's re-fetched again). "Artists Under the Big Top: Perplexed" is one
    instance of a systemic, reproducible class of movies, not an isolated edge case.

- timestamp: 2026-08-29T16:35:00Z
  checked: backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java,
    findSectionIndex() (lines 452-462) and tryFetch() (lines 388-426)
  found: Section extraction only recognizes section headings that exactly (case-insensitively)
    match "Plot" for plot, or "Critical response"/"Reception"/"Critical reception" for critics.
    Short/older/arthouse film articles (like this 1968 film's stub-quality page) commonly lack
    a dedicated section under exactly these names, even when the lead paragraph itself contains
    plot-like content (confirmed: this movie's wiki_summary already reads as a plot synopsis).
  implication: The section-name allowlist is a secondary contributing factor — it's *why*
    wiki_plot/wiki_critics stay null for a genuinely-found, correctly-matched page — but the
    primary defect for G-16-3 is the mismatch between the eligibility query's and the
    WikiRetryOutcome classification's differing definitions of "found," not the section-name
    list itself.

- timestamp: 2026-08-29T16:38:00Z
  checked: .planning/phases/16-bulk-import-correctness-wiki-reload-progress-clarity/16-02-PLAN.md,
    Task 2 body (D-09 section, lines 182-190)
  found: The plan explicitly scoped D-09 as a pure frontend template change ("The push logic
    that builds wikiMovieHistory entries needs no change — p.lastMovieStatus already carries
    the real 3-value backend string") — it took the backend's SUCCESS/NOT_FOUND/FAILED value at
    face value as ground truth and never questioned whether that classification matches user
    expectations for a "page found, no content extracted" movie.
  implication: This explains why the unit test in settings.spec.ts passes and always will —
    it only exercises the frontend's icon-selection logic against a hand-supplied
    `lastMovieStatus` string. It cannot catch this bug because the bug is upstream, in what
    value the backend legitimately computes and sends — the frontend rendering layer is
    correct and faithfully reflects backend intent.

## Resolution

root_cause: |
  Two independently-implemented, conflicting definitions of "Wikipedia data found for this
  movie" coexist in the codebase:

  1. Retry-eligibility (MovieRepository.findEligibleForWikiReload): "found" = wiki_plot OR
     wiki_critics has extracted content. A movie whose article exists but lacks a
     recognized "Plot"/"Critical response" section counts as "not found" and is retried
     forever.
  2. Per-movie outcome classification (WikiReloadService.WikiRetryOutcome /
     doRetryWikipedia()): "found" = WikipediaClient.fetch() located a page at all (didn't
     throw WikipediaNotFoundException) — regardless of whether any content section was
     extracted. This is the value D-09's per-movie history checkmark/icon renders verbatim.

  For "Artists Under the Big Top: Perplexed" (and 40 other movies in the live dataset with
  the identical shape), a real, correctly-matched Wikipedia page WAS found
  (wiki_url/wiki_summary populated), so definition #2 legitimately reports SUCCESS every
  single run — but the article's structure never yields a "Plot" or
  "Critical response"/"Reception"/"Critical reception" section (secondary contributing
  factor: WikipediaClient.findSectionIndex()'s fixed section-name allowlist), so definition
  #1 never considers this movie "done" and keeps re-selecting it on every wiki-reload run
  (amplified by wiki.retry.cooldown-days currently being 0 for dev testing, so there is no
  cooldown window to mask the repeat selection). The user observes the same movie
  perpetually resurfacing at the top of the eligible list (matching definition #1's "still
  not really found" behavior) while it displays a checkmark (matching definition #2's
  "found a page" behavior) — an internally-inconsistent user experience, even though neither
  individual code path is malfunctioning in isolation. The frontend (settings.vue) and its
  unit test are both correct and not implicated: they render exactly the SUCCESS/NOT_FOUND/
  FAILED value the backend legitimately computed and sent.

  Note: this is a distinct defect from gap G-16-2 (duplicate last-row-on-Stop), which is a
  real, separately-confirmed bug in WikiReloadProgressService.complete() re-broadcasting the
  prior movie's state as a new terminal event — that mechanism was investigated and ruled out
  as a cause of G-16-3 (see Eliminated).

fix: (not applied — goal: find_root_cause_only; plan-phase --gaps will design the fix)
verification: n/a
files_changed: []
