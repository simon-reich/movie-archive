---
phase: quick
plan: 260826-pwp
type: execute
wave: 1
depends_on: []
files_modified:
  - backend/src/main/resources/application.properties
autonomous: true
requirements: []

must_haves:
  truths:
    - "wiki.retry.cooldown-days default resolves to 0 when WIKI_RETRY_COOLDOWN_DAYS is unset"
    - "The temporary override is trivially greppable and states the revert value (30)"
  artifacts:
    - path: "backend/src/main/resources/application.properties"
      provides: "wiki.retry.cooldown-days default changed 30 -> 0, with a TEMPORARY comment marking it for revert"
  key_links:
    - from: "application.properties wiki.retry.cooldown-days default"
      to: "WikiReloadService cooldown check (unchanged)"
      via: "Spring @Value/property binding (no code touched)"
      pattern: "cooldown of 0 days means the batch-reload cooldown gate always passes"
---

<objective>
Temporarily set the default for `wiki.retry.cooldown-days` from 30 to 0 in `backend/src/main/resources/application.properties`, so the Wikidata-based batch-reload can immediately re-process previously-failed movies during dev testing, without touching any cooldown logic in code.

Purpose: Unblock dev testing of the new Wikidata-first Wikipedia lookup (Phase 12) against movies that previously failed enrichment and are sitting in the 30-day cooldown window.
Output: A single-line properties value change with an unmistakable TEMPORARY marker comment, easy to grep and revert later.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
</execution_context>

<context>
@backend/src/main/resources/application.properties
</context>

<tasks>

<task type="auto">
  <name>Task 1: Set wiki.retry.cooldown-days default to 0 (TEMPORARY, dev-testing only)</name>
  <files>backend/src/main/resources/application.properties</files>
  <action>
Open `backend/src/main/resources/application.properties` and find line 59:

`wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:30}`

(directly below the existing comment `# Wiki batch-reload (Phase 8: cooldown window + inter-request pacing, D-04/D-08)` on line 58 — leave that comment line unchanged).

Change the default value inside the property placeholder from `30` to `0`, and add one new comment line immediately above the property assigning the change context and revert instruction. The result should read:

`# TEMPORARY (dev-testing) — was 30, set to 0 so batch-reload can immediately re-process previously-failed movies against the new Wikidata-first lookup; revert to 30 when done testing`
`wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:0}`

Do not modify the `wiki.retry.pacing-delay-ms` line below it, the `# Wiki batch-reload ...` comment above it, or any other line in the file. Do not touch `WikiReloadService.java`, any cooldown-checking code, or any test file — this task is a properties-file value change only.
  </action>
  <verify>
    <automated>grep -A1 "TEMPORARY (dev-testing)" /Users/simonreich/git/private/movie-archive/backend/src/main/resources/application.properties | grep -c "WIKI_RETRY_COOLDOWN_DAYS:0"</automated>
  </verify>
  <done>Line above `wiki.retry.cooldown-days` reads the TEMPORARY comment with "was 30" and "revert to 30", and the property resolves to `WIKI_RETRY_COOLDOWN_DAYS:0`. No other line in the file changed.</done>
</task>

</tasks>

<verification>
Run:

```bash
grep -B1 "wiki.retry.cooldown-days=" /Users/simonreich/git/private/movie-archive/backend/src/main/resources/application.properties
```

Expected output:

```
# TEMPORARY (dev-testing) — was 30, set to 0 so batch-reload can immediately re-process previously-failed movies against the new Wikidata-first lookup; revert to 30 when done testing
wiki.retry.cooldown-days=${WIKI_RETRY_COOLDOWN_DAYS:0}
```

Confirm no other lines in the file were changed:

```bash
cd /Users/simonreich/git/private/movie-archive && git diff backend/src/main/resources/application.properties
```

Expected: diff touches only the `wiki.retry.cooldown-days` line (value change) plus the one new comment line inserted above it.
</verification>

<success_criteria>
- `wiki.retry.cooldown-days` default is `0` (was `30`)
- A TEMPORARY marker comment sits directly above the property, stating the original value (30) and the revert instruction
- No other property, no Java source file, and no test file was modified
</success_criteria>

<output>
After completion, create `.planning/quick/260826-pwp-set-wiki-retry-cooldown-days-default-to-/260826-pwp-SUMMARY.md`
</output>
