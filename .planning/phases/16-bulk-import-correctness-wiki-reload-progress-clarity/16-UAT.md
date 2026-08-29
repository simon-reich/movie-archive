---
status: diagnosed
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
source: [16-VERIFICATION.md]
started: 2026-08-29T13:22:55Z
updated: 2026-08-29T16:10:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Reload button re-enables and history clears specifically after a stopped (not just genuinely-completed) run
expected: In the running app: click "Reload missing Wikipedia data" with more than one eligible
  movie, wait for the run to be actively in progress, click "Stop", wait for the terminal SSE
  event to arrive (panel should read "Stopped at X / Y"), then confirm the "Reload missing
  Wikipedia data" button is clickable again (not disabled/greyed out). Click it again and confirm
  the previous run's per-movie history list is cleared before the new run's entries appear.
result: pass

### 2. Per-movie history does not duplicate the last processed title after Stop
expected: When a wiki-reload run is stopped, the per-movie history list shows each processed
  movie exactly once — the last movie processed before Stop took effect must not appear twice
  in two consecutive rows.
result: issue
reported: "beim Stoppen, wenn dann wirklich gestoppt wurde, wird der letzte Eintragfilmtitel nochmal wiederholt. Und quasi in zwei darauf folgenden Zeilen doppelt angezeigt."
severity: major

### 3. NOT_FOUND movies show a distinct icon in per-movie history, not the success checkmark
expected: A movie for which no Wikipedia data was found (NOT_FOUND outcome, e.g. "Artists Under
  the Big Top: Perplexed") shows a distinct neutral "not found" icon/label in the per-movie
  history — never the same checkmark icon used for a genuinely successful wiki-data fetch.
result: issue
reported: "der Film 'Artists Under the Big Top: Perplexed' — zu dem werden keine Daten gefunden, weil wir den 30 Tage Back Off gerade deaktiviert haben, wird er immer wieder als erstes probiert. Der steht dann aber dort mit einem Häkchen-Icon, als wäre das alles erfolgreich gewesen. Dafür wollten wir ja ein eigenes Icon haben, dass man erkennt, dass hier keine Daten gefunden wurden."
severity: major

## Summary

total: 3
passed: 1
issues: 2
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-16-2
  truth: "Per-movie history shows each processed movie exactly once after Stop — no duplicate consecutive row for the last title processed"
  status: failed
  reason: "User reported: beim Stoppen wird der letzte Eintragfilmtitel nochmal wiederholt und in zwei darauf folgenden Zeilen doppelt angezeigt"
  severity: major
  test: 2
  root_cause: "Two cooperating defects. (1) Backend: WikiReloadProgressService.complete() echoes prior.lastMovieTitle()/lastMovieStatus() into the terminal SSE event instead of nulling them — the terminal event always re-describes the same movie the last progress event already described, on every run end (not just Stop). (2) Frontend: settings.vue's SSE handler pushes a wikiMovieHistory row whenever p.lastMovieTitle is truthy, with no !p.complete guard, and useSettings.ts routes both progress and complete events through the same onProgress callback — so the last movie gets pushed twice."
  artifacts:
    - path: "backend/src/main/java/de/moviearchive/admin/WikiReloadProgressService.java"
      issue: "complete() echoes prior.lastMovieTitle()/lastMovieStatus() into the terminal event instead of clearing them"
    - path: "frontend/pages/settings.vue"
      issue: "wikiMovieHistory.value.push(...) has no !p.complete guard, so the terminal event's echoed title/status pushes a duplicate row"
  missing:
    - "Guard the history push in settings.vue with `if (p.lastMovieTitle && !p.complete)` — every processed movie is already reported via its own progress event before the terminal event fires"
    - "Add a regression test in settings.spec.ts firing a realistic progress→complete sequence for the same movie, asserting wikiMovieHistory length stays at 1"
  debug_session: ".planning/debug/16-history-duplicate-on-stop.md"

- gap_id: G-16-3
  truth: "Per-movie history renders 3 distinct states: SUCCESS (checkmark), NOT_FOUND (neutral icon + label), FAILED (X) — D-09"
  status: failed
  reason: "User reported: NOT_FOUND movie ('Artists Under the Big Top: Perplexed') displays with the success checkmark icon instead of a distinct not-found icon"
  severity: major
  test: 3
  root_cause: "NOT a NOT_FOUND-icon rendering bug — the reported movie's real backend status is SUCCESS (Wikipedia page genuinely found), so the checkmark is technically accurate to what was computed. The actual defect is a pre-existing (older than Phase 16) conflict between two independent 'found' definitions: MovieRepository.findEligibleForWikiReload treats a movie as retry-eligible until wiki_plot OR wiki_critics is non-null (content-extraction-based), while WikiReloadService.WikiRetryOutcome.SUCCESS only requires WikipediaClient.fetch() to locate a page at all (existence-based). A found page whose article structure has no section named exactly Plot/Critical response/Reception (WikipediaClient.findSectionIndex()'s fixed allowlist) never satisfies definition #1, so it is retried forever while definition #2 correctly reports SUCCESS every time — confirmed live: 41/305 movies in the dataset share this exact shape (wiki_url set, wiki_plot and wiki_critics both null)."
  artifacts:
    - path: "backend/src/main/java/de/moviearchive/movie/MovieRepository.java"
      issue: "findEligibleForWikiReload's WHERE clause keys retry-eligibility on wiki_plot IS NULL AND wiki_critics IS NULL instead of wiki_url IS NULL — a genuinely-found page with unextractable content sections is retried forever"
    - path: "backend/src/main/java/de/moviearchive/enrichment/WikiReloadService.java"
      issue: "WikiRetryOutcome/doRetryWikipedia() classifies SUCCESS purely on page existence, independent of whether Plot/Critics content was extracted — correct in isolation, but inconsistent with the repository's stricter retry-eligibility definition"
    - path: "backend/src/main/java/de/moviearchive/enrichment/WikipediaClient.java"
      issue: "findSectionIndex()'s fixed Plot/Critical-response section-name allowlist is a contributing factor — genuinely-found articles with differently-named or absent sections never populate wiki_plot/wiki_critics"
  missing:
    - "Reconcile the two 'found' definitions: stop treating a movie with an existing wiki_url as retry-eligible-forever once a page has been found — retrying only helps articles that were never located at all"
    - "Product decision needed: either (a) key retry-eligibility off wiki_url IS NULL instead of wiki_plot/wiki_critics, and/or (b) introduce a distinct history/status state for 'page found, content incomplete' so the checkmark isn't shown for a partial result, and/or (c) broaden WikipediaClient's section-name recognition (e.g. fall back to lead-paragraph text as plot)"
  debug_session: ".planning/debug/16-notfound-icon-shows-checkmark.md"
