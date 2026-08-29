---
status: resolved
trigger: |
  UAT diagnosis (goal: find_root_cause_only) — Phase 15 gap G-15-2.
  Truth: SAVED bulk-import line's card/row is entirely clickable and navigates to
  /movies/{movieId} (D-05); PARSE_ERROR lines are visually distinct from
  AMBIGUOUS/NOT_FOUND (D-11 display half).
  User report (verbatim, German, live browser UAT): "Saved Cards verlinken nicht
  zur Detailpage. Das gibt nichts, wo man draufklicken kann und dann weitergeleitet
  wird. [...]" (PARSE_ERROR display-redesign feedback bundled in the same report is
  OUT OF SCOPE for this investigation per orchestrator instructions — a separate
  planner handles that; this session investigates ONLY why the SAVED card link does
  not work).
created: 2026-08-28T00:00:00Z
updated: 2026-08-28T00:00:00Z
---

## Current Focus

hypothesis: CONFIRMED — see Resolution below.
test: n/a (goal: find_root_cause_only — investigation complete)
expecting: n/a
next_action: n/a — return ROOT CAUSE FOUND to caller

## Symptoms

expected: |
  Open a real bulk-import batch with mixed statuses (SAVED/AMBIGUOUS/NOT_FOUND/
  PARSE_ERROR). Click a SAVED card -> navigates to /movies/{id}.
actual: |
  Nothing happens when clicking a SAVED card in the live browser — no navigation,
  no visible link affordance at all ("Das gibt nichts, wo man draufklicken kann und
  dann weitergeleitet wird").
errors: None reported by user (no console error mentioned); investigated whether one
  exists — see Evidence below (Vue emits a resolvable-component warning, not a
  thrown error, so nothing crashes and nothing is visibly logged unless devtools
  console is open).
reproduction: |
  1. Complete a bulk import with at least one SAVED line (movieId populated).
  2. Open /imports/{batchId} in a real browser (not Vitest/happy-dom).
  3. Click anywhere on a SAVED line's card (grid or list view).
  Expected: navigate to /movies/{movieId}. Actual: nothing happens.
started: Immediately after Phase 15 merged (2026-08-28) — D-05 introduced the
  whole-card-link feature for the first time; no prior phase had this code path.

## Eliminated

- hypothesis: "movieId is not populated in the API response for SAVED lines (backend bug)."
  evidence: |
    Read BulkImportController.getBatchDetail() (lines 219-233): for every line where
    status == SAVED and tmdbId != null, it queries
    movieRepository.findByUserIdAndTmdbId(userId, line.getTmdbId()) and maps to
    Movie::getId. BulkImportService.saveAndUpsert() (line 273) sets tmdbId =
    match.tmdbId() unconditionally on every SAVED line at import time, and
    movieService.initiate() creates/reuses the Movie row for that exact tmdbId+user.
    This is a straightforward, well-tested lookup (Backend test suite passing per
    15-VERIFICATION.md truth #1, 23/23 BulkImportControllerTest). No code path sets
    tmdbId without also causing a matching Movie row to exist. Ruled out as the cause
    of "nothing clickable at all" — even if this were flaky for edge cases, it would
    at most affect SOME saved lines, not produce zero click affordance across the
    board as reported.
  timestamp: 2026-08-28T00:05:00Z

- hypothesis: "A CSS overlay (z-index) sits on top of the card and intercepts all clicks."
  evidence: |
    Read [batchId].vue template (grid: lines 195-289, list: lines 292-386). The only
    absolutely-positioned overlay is the status-icon badge
    (`absolute bottom-0 right-0 p-2 bg-background/70`), which is a small corner badge,
    not a full-card overlay — it cannot intercept clicks across the whole card, and it
    renders identically for every status (would not explain a status-specific
    difference). No other overlay/z-index pattern exists in this file. Ruled out.
  timestamp: 2026-08-28T00:07:00Z

## Evidence

- timestamp: 2026-08-28T00:10:00Z
  checked: "[batchId].vue lines 195-204 (grid) and 292-301 (list) — the whole-card
    link wrapper"
  found: |
    Both the grid and list wrappers use:
      <component
        :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'"
        ...
        :to="movieLinkTarget(line) ?? undefined"
        ...
      >
    `:is` is bound to the **string literal** `'NuxtLink'` (not an imported component
    reference/object), resolved dynamically at runtime via Vue's
    resolveDynamicComponent().
  implication: |
    This is the exact anti-pattern documented in multiple open/closed Nuxt GitHub
    issues (nuxt/nuxt#13659 "Can't use is=\"NuxtLink\" with component",
    nuxt/nuxt#23450 "<component is=\"NuxtLink\" ...> doesn't render",
    nuxt/nuxt#10545, nuxt/nuxt#22206, nuxt/framework#4098): Nuxt 3's build-time
    component-scanning only injects an import/registration for `NuxtLink` into a
    given SFC when the template contains a LITERAL `<NuxtLink>` tag (AST-level tag
    scan, tree-shaking optimization — avoids bundling every built-in component into
    every page). A string used only inside a JS expression bound to `:is` is invisible
    to that scan.

- timestamp: 2026-08-28T00:12:00Z
  checked: "grep for literal `<NuxtLink` tag usage anywhere in frontend/pages/imports/[batchId].vue"
  found: |
    Zero literal `<NuxtLink>` tag occurrences in this file. The ONLY reference to the
    string "NuxtLink" anywhere in the file is inside the two `:is="... ? 'NuxtLink' :
    'div'"` ternary expressions. Every OTHER page/component in the app that links
    (pages/index.vue, pages/add.vue, pages/imports/index.vue, components/MovieCard.vue,
    components/MovieListItem.vue, components/MovieOfTheDay.vue, components/AppNav.vue,
    components/DashboardStats.vue, plus 6 auth pages) uses `<NuxtLink to="...">` as a
    literal template tag — which IS correctly picked up by Nuxt's compiler.
  implication: |
    [batchId].vue is the only file in the codebase using the dynamic-string `:is`
    pattern for NuxtLink, and it is therefore the only file affected by this Nuxt
    limitation. Confirms this is a localized, novel bug introduced by Phase 15's D-05
    implementation, not a pre-existing/systemic issue.

- timestamp: 2026-08-28T00:14:00Z
  checked: "git log -p -- frontend/pages/imports/[batchId].vue"
  found: |
    The diff introducing this file's whole-card-link feature shows the wrapper
    changing from a plain `<div ...>` (pre-Phase-15) directly to
    `<component :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'" ...>` — there was no
    intermediate state and no prior usage of NuxtLink in this file at all.
  implication: |
    Confirms the bug was introduced in Phase 15 (Plan 15-01, D-05 implementation),
    consistent with "started: immediately after Phase 15 merged."

- timestamp: 2026-08-28T00:16:00Z
  checked: "WebSearch: Nuxt 3 component :is=\"'NuxtLink'\" dynamic component behavior
    at runtime, and Nuxt GitHub issue #13659"
  found: |
    Community-confirmed behavior: when Nuxt cannot statically resolve/bundle
    `NuxtLink` for a file (because the file never uses it as a literal tag), the
    dynamic `<component :is="'NuxtLink'">` at runtime falls through Vue's
    resolveDynamicComponent() resolution chain, fails to find a registered component
    named "NuxtLink", and Vue treats it as an unknown custom element — rendered
    verbatim as a literal, unstyled `<nuxtlink to="/movies/...">` HTML tag (not an
    `<a>`), accompanied by a Vue dev-mode console warning
    ("Failed to resolve component: NuxtLink") but NO thrown error (matches user
    report of no visible error — only visible if devtools console happened to be
    open). A `<nuxtlink>` custom element has no href, no default click/navigation
    behavior, and typically no visual affordance — exactly matching "nichts, wo man
    draufklicken kann und dann weitergeleitet wird" (nothing clickable, no
    navigation). The maintainers' documented workarounds are either
    `const NuxtLink = resolveComponent('NuxtLink')` in `<script setup>` (capturing a
    literal string argument, which Nuxt's compiler CAN statically detect even without
    a template tag) and binding `:is="condition ? NuxtLink : 'div'"` to the resolved
    component object, or referencing `<NuxtLink>` literally elsewhere in the same SFC
    so the compiler injects the import.
  implication: |
    Root cause mechanism fully confirmed via independent, authoritative sources
    (Nuxt's own issue tracker) — not merely inferred from code reading.

- timestamp: 2026-08-28T00:18:00Z
  checked: "frontend/test/unit/pages/imports-batchId.spec.ts lines 32-35 and 85-95
    (mountPage() global.stubs) — why did the Vitest suite pass despite this bug?"
  found: |
    The test explicitly stubs NuxtLink:
      const NUXT_LINK_STUB = { template: '<a :href="to"><slot /></a>', props: ['to'] }
      mount(BatchDetailPage, { global: { stubs: { ..., NuxtLink: NUXT_LINK_STUB } } })
    Vue Test Utils' `global.stubs` mechanism resolves a named stub by globally
    registering it on the mounted test app instance (documented VTU behavior:
    "resolve globally-registered components with an empty stub" / custom template).
    This global registration is exactly the kind of runtime component-name lookup
    that resolveDynamicComponent('NuxtLink') needs and DOES find in the test
    environment — because VTU registers it unconditionally, independent of whether
    the SFC contains a literal `<NuxtLink>` tag. The real Nuxt runtime has no
    equivalent unconditional global registration for `NuxtLink`; it only gets
    per-file registration triggered by literal tag usage at compile time.
  implication: |
    This is precisely why 15-VERIFICATION.md's truth #1 was marked VERIFIED (test
    'wraps a SAVED line in a whole-card link to /movies/{movieId}' passed, 16/16
    suite green) even though the feature is completely broken in a real browser: the
    test's own NuxtLink stub papers over the exact runtime resolution gap that
    breaks production. The verification's code read also missed this because
    `:is="movieLinkTarget(line) ? 'NuxtLink' : 'div'"` LOOKS correct by inspection —
    the failure is a Nuxt build-time/runtime resolution quirk invisible to static
    code reading, only exposed by an actual compiled Nuxt app in a real browser
    (exactly the class of check 15-VERIFICATION.md itself flagged as "not
    automatable" / requiring human browser verification for this very truth).

## Resolution

root_cause: |
  frontend/pages/imports/[batchId].vue uses
  `<component :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'" :to="movieLinkTarget(line) ?? undefined">`
  in both the grid view (line 196-197) and list view (line 293-294) wrappers. `:is` is
  bound to the bare string `'NuxtLink'`, and this file never references `<NuxtLink>`
  as a literal template tag anywhere. Nuxt 3's build-time component auto-import only
  registers `NuxtLink` (and other built-ins) into a given SFC's compiled output when
  it detects a literal `<NuxtLink>` tag in that file's template AST (a tree-shaking
  optimization, documented Nuxt limitation — see nuxt/nuxt#13659, #23450, #10545,
  #22206). Because the string is only ever used inside a JS ternary expression bound
  to `:is`, Nuxt's compiler never injects the registration for this file, so at
  runtime Vue's resolveDynamicComponent('NuxtLink') fails to find a component named
  "NuxtLink" and falls back to rendering an unknown custom element literally as
  `<nuxtlink to="/movies/{id}">` — not an `<a>` tag — which has no href, no default
  click/navigation behavior, and no visual affordance. This exactly reproduces the
  user's report: nothing clickable, no navigation, no visible error (only a Vue
  dev-mode console warning, easy to miss).

  This is a single, localized root cause (code-category bug — no AND-gate: config,
  environment, and data are all ruled out; every SAVED line is affected identically
  regardless of movieId value, batch, or environment, and it reproduces
  deterministically in any real Nuxt-compiled browser session).

  Why prior verification (15-VERIFICATION.md) claimed this worked: the Vitest test
  suite (imports-batchId.spec.ts) explicitly registers a `NuxtLink` stub via Vue Test
  Utils' `global.stubs`, which globally registers a component under that exact name
  on the test app instance — independent of literal-tag usage. This masks the exact
  runtime resolution gap that breaks the real (un-stubbed) Nuxt app, so the test
  passes green while the feature is fully broken in production. The verifier's code
  read also could not catch this because the `:is="condition ? 'NuxtLink' : 'div'"`
  pattern reads as correct by static inspection — the failure is a Nuxt-specific
  compile-time/runtime component-resolution quirk, not a logic bug visible from the
  source alone.
fix: Applied in Phase 15, Plan 15-04 — frontend/pages/imports/[batchId].vue now captures `const NuxtLink = resolveComponent('NuxtLink')` in `<script setup>` and binds `:is="movieLinkTarget(line) ? NuxtLink : 'div'"` using the resolved component reference instead of the bare string `'NuxtLink'`.
verification: Confirmed by reading current frontend/pages/imports/[batchId].vue (resolveComponent pattern present at lines ~22, 240, 341) and 15-04-PLAN.md's regression test asserting `resolveComponent('NuxtLink')` is present.
files_changed: [frontend/pages/imports/[batchId].vue (Phase 15, Plan 15-04)]
