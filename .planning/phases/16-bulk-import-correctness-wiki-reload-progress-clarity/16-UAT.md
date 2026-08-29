---
status: complete
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
source: [16-VERIFICATION.md]
started: 2026-08-29T13:22:55Z
updated: 2026-08-29T15:45:00Z
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
  artifacts: []
  missing: []

- gap_id: G-16-3
  truth: "Per-movie history renders 3 distinct states: SUCCESS (checkmark), NOT_FOUND (neutral icon + label), FAILED (X) — D-09"
  status: failed
  reason: "User reported: NOT_FOUND movie ('Artists Under the Big Top: Perplexed') displays with the success checkmark icon instead of a distinct not-found icon"
  severity: major
  test: 3
  artifacts: []
  missing: []
