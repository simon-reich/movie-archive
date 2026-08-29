---
status: testing
phase: 16-bulk-import-correctness-wiki-reload-progress-clarity
source: [16-VERIFICATION.md]
started: 2026-08-29T13:22:55Z
updated: 2026-08-29T13:22:55Z
---

## Current Test

number: 1
name: Reload button re-enables and history clears specifically after a stopped (not just genuinely-completed) run
expected: |
  Button re-enables at the same moment the "Stopped at X / Y" text appears (no extra delay
  or stuck-disabled state); a second click clears the old history.
awaiting: user response

## Tests

### 1. Reload button re-enables and history clears specifically after a stopped (not just genuinely-completed) run
expected: In the running app: click "Reload missing Wikipedia data" with more than one eligible
  movie, wait for the run to be actively in progress, click "Stop", wait for the terminal SSE
  event to arrive (panel should read "Stopped at X / Y"), then confirm the "Reload missing
  Wikipedia data" button is clickable again (not disabled/greyed out). Click it again and confirm
  the previous run's per-movie history list is cleared before the new run's entries appear.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
