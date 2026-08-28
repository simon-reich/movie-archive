---
created: 2026-08-28T12:15:00.000Z
title: Create a dedicated API-contract doc to prep for a future Flutter port
area: docs
severity: minor
files: []
---

## Problem

User's longer-term plan: reimplement the frontend as a Flutter app later, reusing the existing
Spring Boot backend as-is (Flutter would just be a new client of the same REST/SSE API). Raised
while discussing whether GSD's automatic `LEARNINGS.md` phase-extraction mechanism could help
prep for this.

Conclusion from that discussion: `LEARNINGS.md` isn't the right tool — it's designed for
cross-phase pattern graduation within this GSD project, not cross-platform portability, and it
would mix genuinely transferable backend-API knowledge (endpoints, payload shapes, SSE event
formats, auth/ownership rules, rate-limit/pacing timing nuances) with Vue-specific frontend
implementation details that are irrelevant to a Flutter rewrite.

## Solution

TBD — when this becomes active work, create a dedicated `.planning/API-CONTRACT.md` (or similar)
documenting, backend-endpoint by backend-endpoint:
- Request/response shapes (including SSE event payload shapes like `WikiReloadProgressService.ProgressState`)
- Auth/ownership semantics (JWT subject → path userId matching, 403 conditions)
- Non-obvious timing/rate-limit behavior a new client needs to replicate correctly (e.g.
  wiki-reload's pacing delay, cooldown windows, Wikidata SPARQL chunking, bulk-import's
  progress polling contract)

Scope this only to what a NEW client (Flutter) would need to correctly consume the existing
backend — explicitly excludes Vue/Nuxt-specific implementation details (composables, component
structure), which don't transfer.

Not urgent — no concrete Flutter work has started; this is prep for when that work begins.
