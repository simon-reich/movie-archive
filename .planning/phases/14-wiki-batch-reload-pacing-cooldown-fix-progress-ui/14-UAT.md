---
status: partial
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
source: [14-VERIFICATION.md]
started: 2026-08-27T18:20:00.000Z
updated: 2026-08-28T00:00:00.000Z
---

## Current Test

[testing paused — pacing-value experiment requested by user before phase close]

## Tests

### 1. Stop-mid-run halts the batch loop
expected: |
  Triggering a wiki-reload with 3+ eligible movies, then clicking Stop partway through, results in
  fewer than all eligible movies being processed/indexed. The visible history list stops growing
  after Stop. A fresh "Reload missing Wikipedia data" click afterward resumes cleanly (the
  remaining, not-yet-processed movies stay eligible).
result: pass

Confirmed live against real data (simon@dev.org, 368-382 eligible movies, cooldown temporarily
set to 0 via WIKI_RETRY_COOLDOWN_DAYS for testing) across two separate stop attempts — halted
cleanly at processedCount=15/382 and again at processedCount=4/368, both well short of the full
eligible count, with the movie history list correctly stopping growth at the halt point. A fresh
Reload click afterward continued against the remaining eligible movies via the existing
cooldown-eligibility query, as expected (D-09 — no dedicated resume state needed).

Along the way this session found and fixed five real bugs surfaced only under live UAT (mocked
tests couldn't reproduce them): Stop button not appearing during the Wikidata prefetch phase,
Wikidata SPARQL resolution firing all chunks in a rate-limit-tripping burst instead of
interleaved with movie processing, ETA massively understated (didn't include the per-movie
pacing delay), the Stop button's "Stopping..." state reverting instantly instead of reflecting
the real wait, and a `disabled` prop type error caught by the frontend typecheck. All five are
committed (see git log `fix(14): ...` from 2026-08-27/28). A sixth issue — AuthorizationDeniedException
log noise on SSE-stream completion, pre-existing and shared with bulk-import's progress endpoint —
was filed as a follow-up todo rather than fixed here (out of this phase's scope).

## Summary

total: 1
passed: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none]

## Deferred Follow-Ups

- test: 1 (adjacent finding, not a gap)
  idea: "Try wiki.retry.pacing-delay-ms=20000 (down from the 30000 default) to see if throughput
    can be improved without re-tripping the Wikipedia/Wikidata rate limits — user wants to try
    this live before closing the phase."
  deferred_at: 2026-08-28
