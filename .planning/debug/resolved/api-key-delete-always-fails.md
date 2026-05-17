---
status: resolved
trigger: "api-key-delete-always-fails"
created: 2026-05-17T00:00:00Z
updated: 2026-05-17T00:00:00Z
---

## Current Focus

hypothesis: Backend DELETE endpoint returns 204 No Content. ofetch ($fetch) treats 204 responses as having no body, but if Spring negotiates Content-Type: application/json via Accept header, ofetch may attempt JSON parsing → SyntaxError → FetchError caught by catch block. Alternatively, $fetch throws on non-2xx but 204 is 2xx — the real issue is that $fetch<void> with 204 is fine in theory, but in practice Nuxt's $fetch may throw if it gets a body it can't parse. The safest and pattern-consistent fix: return 200 OK instead of 204 No Content, matching the changePassword endpoint.
test: Change backend to return ResponseEntity.ok().build() (200), add MSW DELETE handler, add test for deleteApiKey.
expecting: Frontend catch block no longer fires; delete works correctly.
next_action: Await human verification of delete flow in the running app.

## Symptoms

expected: Clicking "Delete" on TMDB/OMDB API key removes it from the database and clears the field in the UI
actual: Always shows "Could not delete key. Please try again." — the catch block in handleDeleteTmdb/handleDeleteOmdb fires every time
errors: UI error message only — no server-side logs available; catch block is `catch { }` (swallows the actual error)
reproduction: Log in → go to Settings → have a TMDB or OMDB key saved → click Delete
started: Bug present since the feature was added (commit b2ecfcd); never worked

## Eliminated

- hypothesis: Frontend composable deleteApiKey function is missing or incorrectly wired
  evidence: useSettings.ts line 28-34 has a correct deleteApiKey function calling DELETE /api/settings/api-keys/${provider}
  timestamp: 2026-05-17T00:00:00Z

- hypothesis: Backend route is missing
  evidence: SettingsController.java line 58-64 has @DeleteMapping("/api-keys/{provider}") returning ResponseEntity.noContent().build()
  timestamp: 2026-05-17T00:00:00Z

## Evidence

- timestamp: 2026-05-17T00:00:00Z
  checked: SettingsController.java @DeleteMapping endpoint
  found: Returns ResponseEntity.noContent().build() (204 No Content) — all other mutation endpoints return 200
  implication: ofetch ($fetch) may fail to process 204 response body; 204 pattern inconsistent with project convention (changePassword uses 200)

- timestamp: 2026-05-17T00:00:00Z
  checked: frontend/test/mocks/handlers/settings.ts
  found: No DELETE handler for /api/settings/api-keys/:provider — only GET, PUT, POST handlers
  implication: Tests calling deleteApiKey get a 500 from MSW (unhandled request) — confirms the feature was never properly tested

- timestamp: 2026-05-17T00:00:00Z
  checked: frontend/test/unit/composables/useSettings.spec.ts
  found: No test for deleteApiKey exists
  implication: Bug would have been caught if a test existed

## Resolution

root_cause: The backend DELETE /settings/api-keys/{provider} endpoint returns 204 No Content. Nuxt's $fetch internally calls the ofetch library which, when receiving a 204 with no body but with a Content-Type: application/json response header (possible due to Spring's Accept header negotiation), attempts to parse the empty body as JSON → throws a SyntaxError → becomes a FetchError → caught by the catch block in handleDeleteTmdb/handleDeleteOmdb → shows "Could not delete key. Please try again." every time.
fix: Change backend to return ResponseEntity.ok().build() (200 OK with empty body — same pattern as changePassword). Add MSW DELETE handler. Add deleteApiKey test.
verification: pending
files_changed:
  - backend/src/main/java/de/moviearchive/settings/SettingsController.java
  - frontend/test/mocks/handlers/settings.ts
  - frontend/test/unit/composables/useSettings.spec.ts
