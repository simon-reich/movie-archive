---
status: testing
phase: 14-wiki-batch-reload-pacing-cooldown-fix-progress-ui
source: [14-VERIFICATION.md]
started: 2026-08-27T18:20:00.000Z
updated: 2026-08-27T18:20:00.000Z
---

## Current Test

number: 1
name: Stop-mid-run halts the batch loop
expected: |
  Triggering a wiki-reload with 3+ eligible movies, then clicking Stop partway through, results in
  fewer than all eligible movies being processed/indexed. The visible history list stops growing
  after Stop. A fresh "Reload missing Wikipedia data" click afterward resumes cleanly (the
  remaining, not-yet-processed movies stay eligible).
awaiting: user response

## Tests

### 1. Stop-mid-run halts the batch loop
expected: |
  Triggering a wiki-reload with 3+ eligible movies, then clicking Stop partway through, results in
  fewer than all eligible movies being processed/indexed. The visible history list stops growing
  after Stop. A fresh "Reload missing Wikipedia data" click afterward resumes cleanly (the
  remaining, not-yet-processed movies stay eligible).
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
