---
phase: quick
plan: 260519-fcg
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/composables/useAuth.ts
  - frontend/composables/useSearch.ts
  - frontend/composables/useSettings.ts
  - frontend/pages/add.vue
  - frontend/pages/index.vue
  - frontend/test/unit/pages/add.spec.ts
  - frontend/test/unit/pages/login.spec.ts
  - frontend/components/FilterPanel.vue
  - frontend/components/InputText.vue
  - frontend/components/MovieCard.vue
  - frontend/components/MovieListItem.vue
  - frontend/components/MovieOfTheDay.vue
  - frontend/components/TrailerEmbed.vue
  - frontend/pages/movies/[id].vue
  - frontend/pages/settings.vue
  - frontend/pages/forgot-password.vue
  - frontend/pages/login.vue
  - frontend/pages/reset-password.vue
  - frontend/pages/signup.vue
autonomous: true
requirements: []

must_haves:
  truths:
    - "ESLint exits 0 with no errors"
    - "All 11 error-level issues are resolved"
    - "All 29 warning-level issues are resolved"
  artifacts:
    - path: "frontend/composables/useAuth.ts"
      provides: "unused router variable removed"
    - path: "frontend/pages/index.vue"
      provides: "single template root"
  key_links:
    - from: "CI lint step"
      to: "exit 0"
      via: "pnpm lint"
      pattern: "no errors, no warnings"
---

<objective>
Fix all 11 ESLint errors and 29 warnings reported by CI so the lint step passes with exit code 0.

Purpose: Unblock CI — the lint check is currently failing and blocking merges.
Output: Clean codebase with zero ESLint errors and zero warnings.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@frontend/composables/useAuth.ts
@frontend/composables/useSearch.ts
@frontend/composables/useSettings.ts
@frontend/pages/add.vue
@frontend/pages/index.vue
@frontend/test/unit/pages/add.spec.ts
@frontend/test/unit/pages/login.spec.ts
</context>

<tasks>

<task type="auto">
  <name>Task 1: Fix all 11 ESLint errors</name>
  <files>
    frontend/composables/useAuth.ts,
    frontend/composables/useSearch.ts,
    frontend/composables/useSettings.ts,
    frontend/pages/add.vue,
    frontend/pages/index.vue,
    frontend/test/unit/pages/add.spec.ts,
    frontend/test/unit/pages/login.spec.ts
  </files>
  <action>
Apply the following targeted fixes — one per error, in file order:

**useAuth.ts line 5** — `@typescript-eslint/no-unused-vars`: Remove `const router = useRouter()`. The composable uses `navigateTo()` (Nuxt auto-import) directly, not `router`, so the line is dead code.

**useSearch.ts line 168** — `@typescript-eslint/no-dynamic-delete`: Replace `delete q[key]` with an explicit destructure pattern:
```ts
const { [key]: _removed, ...rest } = q
Object.assign(q, rest)
// then clear q's own keys and reassign rest
```
A cleaner idiomatic fix: rebuild the query object without the key instead of mutating with delete:
```ts
function updateFilter(key: string, value: string | string[] | null): void {
  const q: Record<string, string | string[]> = {}
  for (const [k, v] of Object.entries(route.query)) {
    if (k !== key && v !== undefined) q[k] = v as string | string[]
  }
  if (value !== null && value !== '' && !(Array.isArray(value) && value.length === 0)) {
    q[key] = value as string | string[]
  }
  q.page = '0'
  router.replace({ query: q })
}
```

**useSettings.ts lines 13, 29, 37, 49** — `@typescript-eslint/no-invalid-void-type`: `$fetch<void>(...)` is correct; the problem is `Promise<void>` as the explicit return type annotation on the async function is fine, but `$fetch<void>` triggers the rule because `void` is used as a generic type argument in a non-return position. Fix by removing the explicit generic from each `$fetch<void>` call — TypeScript infers it:
- `await $fetch<void>(...)` → `await $fetch(...)`
- Do this for all four occurrences (saveApiKey, deleteApiKey, changePassword, changeEmail).

**pages/add.vue line 5** — `@typescript-eslint/no-unused-vars`: Remove the import of `ButtonPrimary`. Check the template for any `<ButtonPrimary` usage; if absent, the import is truly unused and can be deleted.

**pages/add.vue line 40** — `@typescript-eslint/no-explicit-any`: Replace `catch (e: any)` with `catch (e: unknown)` and then narrow:
```ts
} catch (e: unknown) {
  const err = e as { status?: number }
  if (err?.status === 422) {
    searchError.value = 'No TMDB key configured. Add your key in Settings.'
  } else {
    searchError.value = 'Search failed. Please try again.'
  }
}
```

**pages/index.vue line 23** — `vue/no-multiple-template-root`: The template has `<Head>` and `<div>` as two root-level siblings. Wrap both in a single `<div>` or `<template>` fragment:
```html
<template>
  <div>
    <Head><Title>Dashboard — MovieArchive</Title></Head>
    <div class="min-h-screen bg-background">
      ...rest of template unchanged...
    </div>
  </div>
</template>
```

**test/unit/pages/add.spec.ts line 16** — `@typescript-eslint/no-unused-vars`: Remove the `template` variable assignment. Line 16 is `const template = String(AddPage.__hmrId ?? AddPage)`. The variable is assigned but never read. Either delete the line entirely or if the `String(...)` call has a side-effect you want to keep, replace with a void expression: `void String(AddPage.__hmrId ?? AddPage)`. Deleting the line is cleaner.

**test/unit/pages/login.spec.ts line 28** — `@typescript-eslint/no-dynamic-delete`: Replace `delete mockRouteQuery[key]` inside the `for` loop with a rebuild approach:
```ts
// Clear query params between tests
Object.keys(mockRouteQuery).forEach(k => {
  // eslint-disable-next-line @typescript-eslint/no-dynamic-delete — acceptable: test cleanup
})
```
Actually, since this is test code clearing a plain object, the cleanest fix that avoids the rule is to replace the `for...of...delete` pattern with object replacement: if `mockRouteQuery` is a `ref` or plain object declared with `let`, reassign it. If it is `const`, replace all keys by iterating and setting to undefined then using a helper. Best practical fix — replace the for loop with:
```ts
Object.keys(mockRouteQuery).forEach(k => (mockRouteQuery as Record<string, unknown>)[k] = undefined)
```
...but that leaves undefined keys. The correct fix depends on how `mockRouteQuery` is declared. Read the top of login.spec.ts to find its declaration, then either:
- If `let mockRouteQuery = {}`: reassign `mockRouteQuery = {}` in beforeEach instead of the delete loop.
- If `const mockRouteQuery: Record<string, string> = {}`: use `Object.keys(mockRouteQuery).forEach(k => { const rec = mockRouteQuery as Record<string, string | undefined>; rec[k] = undefined })` — but that leaves keys. Prefer creating a fresh object ref. If the mock is set up via `vi.mock` capturing the reference, you cannot reassign but you can clear: write a helper `clearObject(obj: Record<string, unknown>) { for (const k of Object.keys(obj)) { obj[k] = undefined as unknown as string } }` with an eslint-disable comment scoped to that helper only.
  
The simplest all-cases fix: add `// eslint-disable-next-line @typescript-eslint/no-dynamic-delete` comment on the line before `delete mockRouteQuery[key]`. This is acceptable for test scaffolding cleanup code where the pattern is intentional and there is no cleaner alternative without restructuring the mock setup.
  </action>
  <verify>
    <automated>cd /Users/simonreich/git/private/movie-archive/frontend && pnpm lint 2>&1 | grep -E "error|warning" | head -20</automated>
  </verify>
  <done>Zero error-level ESLint violations remain across the seven modified files.</done>
</task>

<task type="auto">
  <name>Task 2: Fix all 29 ESLint warnings (self-closing tags, attribute order, attribute hyphenation)</name>
  <files>
    frontend/components/FilterPanel.vue,
    frontend/components/InputText.vue,
    frontend/components/MovieCard.vue,
    frontend/components/MovieListItem.vue,
    frontend/components/MovieOfTheDay.vue,
    frontend/components/TrailerEmbed.vue,
    frontend/pages/add.vue,
    frontend/pages/movies/[id].vue,
    frontend/pages/settings.vue,
    frontend/pages/forgot-password.vue,
    frontend/pages/login.vue,
    frontend/pages/reset-password.vue,
    frontend/pages/signup.vue,
    frontend/pages/index.vue
  </files>
  <action>
Apply the following mechanical fixes:

**vue/html-self-closing** — Void HTML elements (`<input>`, `<img>`, `<hr>`) must be self-closed in Vue SFCs. For each occurrence below, add the trailing `/` before `>`:
- `FilterPanel.vue` lines 155, 173, 191, 201, 218, 229, 243, 318: change `<input ` tags to self-closing `<input ... />`
- `InputText.vue` line 29: `<input ... />` (self-close)
- `MovieCard.vue` line 31: `<img ... />` (self-close)
- `MovieListItem.vue` line 30: `<img ... />` (self-close)
- `MovieOfTheDay.vue` line 26: `<img ... />` (self-close)
- `TrailerEmbed.vue` line 36: `<img ... />` (self-close)
- `pages/add.vue` line 137: `<img ... />` (self-close)
- `pages/movies/[id].vue` lines 127, 148, 309: `<img ... />` and `<input ... />` (self-close)
- `pages/settings.vue` lines 294, 369: `<hr ... />` (self-close)
- `pages/index.vue` line 69: `<img ... />` (self-close)

**vue/attributes-order** — Vue requires static attributes before event listeners. Fix each occurrence by moving `novalidate` (or other static attrs) before `@submit.prevent`:
- `pages/forgot-password.vue` line 48: move `novalidate` before `@submit.prevent`
- `pages/login.vue` line 76: move static attrs before event handlers
- `pages/reset-password.vue` line 119: move `novalidate` before `@submit.prevent`
- `pages/settings.vue` lines 224, 252: reorder attributes so static come first
- `pages/signup.vue` line 60: move `novalidate` before `@submit.prevent`

Read each file at the flagged lines to see the actual attribute order, then swap the order so static/structural attributes precede event-binding attributes (`@`, `v-on`).

**vue/attribute-hyphenation** — Props on custom components must use kebab-case in templates:
- `pages/index.vue` lines 37-39 inside `<DashboardStats>`: rename `:totalFilms` → `:total-films`, `:topGenres` → `:top-genres`, `:languageBreakdown` → `:language-breakdown`.

Note: The DashboardStats component's props are defined in camelCase in `<script setup>` — Vue auto-handles the mapping from kebab-case in the template to camelCase in the component definition. No changes needed in the component itself.
  </action>
  <verify>
    <automated>cd /Users/simonreich/git/private/movie-archive/frontend && pnpm lint 2>&1; echo "Exit: $?"</automated>
  </verify>
  <done>pnpm lint exits 0 with no errors and no warnings. CI lint step passes.</done>
</task>

</tasks>

<verification>
Run the full lint suite after both tasks:

```bash
cd /Users/simonreich/git/private/movie-archive/frontend && pnpm lint
```

Expected: exit code 0, no output lines containing "error" or "warning".

Also run unit tests to confirm the test file changes did not break assertions:

```bash
cd /Users/simonreich/git/private/movie-archive/frontend && pnpm test:unit 2>&1 | tail -20
```
</verification>

<success_criteria>
- `pnpm lint` exits 0
- All 11 error-level violations resolved
- All 29 warning-level violations resolved
- `pnpm test:unit` still passes (no regressions from test file edits)
</success_criteria>

<output>
After completion, create `.planning/quick/260519-fcg-fix-all-eslint-errors-and-warnings-from-/260519-fcg-SUMMARY.md`
</output>
