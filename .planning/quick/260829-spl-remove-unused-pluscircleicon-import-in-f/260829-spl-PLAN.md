---
phase: 260829-spl-remove-unused-pluscircleicon-import-in-f
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/pages/movies/[id].vue
autonomous: true
requirements: []

estimate:
  tokens: 8000
  raw_tokens: 8000
  tasks: 1
  confidence: high

must_haves:
  truths:
    - "Frontend ESLint job passes with no `no-unused-vars` error for PlusCircleIcon in frontend/pages/movies/[id].vue"
  artifacts:
    - frontend/pages/movies/[id].vue
  key_links:
    - "lucide-vue-next import list only includes icons actually referenced in the file's template/script"
---

<objective>
Remove the unused `PlusCircleIcon` import from `frontend/pages/movies/[id].vue:3` that is currently failing the Frontend ESLint CI job with `'PlusCircleIcon' is defined but never used`.

Purpose: Unblock CI — this was pre-documented as known tech debt in `.planning/milestones/v1.1-phases/15-.../deferred-items.md` but never fixed.
Output: `frontend/pages/movies/[id].vue` with a clean `lucide-vue-next` import (only `TrashIcon`), ESLint passing.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@frontend/pages/movies/[id].vue
</context>

<tasks>

<task type="auto">
  <name>Task 1: Remove unused PlusCircleIcon import</name>
  <files>frontend/pages/movies/[id].vue</files>
  <action>
    On line 3, `import { TrashIcon, PlusCircleIcon } from 'lucide-vue-next'` currently imports `PlusCircleIcon`, but a full-file search confirms `PlusCircleIcon` is never referenced anywhere else in the script or template of this file — only `TrashIcon` is used (in the "Remove from archive" button near the bottom of the template). Change the import to `import { TrashIcon } from 'lucide-vue-next'`, removing `PlusCircleIcon` entirely. Do not touch any other import or line in the file. If a re-check during implementation finds `PlusCircleIcon` genuinely referenced somewhere (e.g. inside a `v-if` branch not yet seen), use it there instead of deleting the import — but the current read of the file shows no such usage.
  </action>
  <verify>
    <automated>cd frontend && pnpm exec eslint pages/movies/\[id\].vue</automated>
  </verify>
  <done>ESLint reports zero errors/warnings for `frontend/pages/movies/[id].vue`; `PlusCircleIcon` no longer appears anywhere in the file; `TrashIcon` import and its usage in the delete button remain unchanged and functional.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

None — this is a lint-only, no-op-at-runtime fix touching a single unused import statement. No new trust boundary, no user input, no data flow change.

## STRIDE Threat Register

No new threats introduced. Removing an unused import has no security-relevant effect (no behavior change, no new dependency, no new code path).
</threat_model>

<verification>
Run `cd frontend && pnpm run lint` (full project lint) and confirm no error is reported for `frontend/pages/movies/[id].vue`. Confirm the file still renders/behaves identically (delete button with `TrashIcon` still present and wired to `deleteModalOpen = true`).
</verification>

<success_criteria>
- `frontend/pages/movies/[id].vue` no longer imports `PlusCircleIcon`
- `pnpm run lint` (or the scoped eslint command) exits 0 for this file
- No functional/behavioral change to the page (TrashIcon delete button unaffected)
</success_criteria>

<output>
Create `.planning/quick/260829-spl-remove-unused-pluscircleicon-import-in-f/260829-spl-SUMMARY.md` when done
</output>
