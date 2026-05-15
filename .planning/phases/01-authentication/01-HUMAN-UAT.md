---
status: partial
phase: 01-authentication
source: [01-VERIFICATION.md]
started: 2026-05-15T00:00:00Z
updated: 2026-05-15T00:00:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. End-to-end browser auth flow
expected: Dashboard redirects to /login; Mailpit receives verification email; login issues JWT + HttpOnly refresh cookie; browser navigates to / after login
result: [pending]

### 2. Option D visual palette
expected: Warm off-white background (#FAF7F2), terracotta buttons (#C84B31), square corners, "MovieArchive" heading above the auth card at /login
result: [pending]

### 3. Concurrent tab token rotation
expected: Simultaneous refresh requests from two browser tabs both succeed within the 5-second grace_until window
result: [pending]

### 4. Password reset single-use enforcement
expected: Using the same reset link twice returns a "Token already used" error on the second attempt
result: [pending]

### 5. Logout session termination
expected: After logout, a subsequent token refresh returns 401 and the HttpOnly cookie is cleared in the browser
result: [pending]

## Summary

total: 5
passed: 0
issues: 0
pending: 5
skipped: 0
blocked: 0

## Gaps
