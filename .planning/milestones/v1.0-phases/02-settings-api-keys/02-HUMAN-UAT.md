---
status: complete
phase: 02-settings-api-keys
source: [02-VERIFICATION.md]
started: 2026-05-16T14:00:00Z
updated: 2026-08-29T00:00:00Z
---

## Current Test

[all tests complete]

## Tests

### 1. Settings link visible in AppNav when logged in
expected: Settings link appears in the nav bar when user is authenticated; hidden when logged out

result: passed

### 2. Inline "Saved" state after API key save
expected: After saving a TMDB or OMDB key, an inline "Saved" confirmation appears next to the respective field (D-06)

result: passed

### 3. Inline error below key field on 422 response
expected: When an invalid API key is submitted and the backend returns 422, an inline error message appears below the field (D-10)

result: passed

### 4. Inline "Check your inbox" message after email change
expected: After submitting an email change, an inline success message "Check your inbox to confirm the change" appears (D-07)

result: passed

### 5. Inline error on wrong password during password change
expected: When the current password is incorrect during a password change, an inline error appears below the password field (D-10)

result: passed

### 6. Unauthenticated redirect from /settings to /login
expected: Navigating to /settings when not logged in redirects to /login

result: passed

## Summary

total: 6
passed: 6
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

None.
