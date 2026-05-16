# Phase 2: Settings & API Keys - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-16
**Phase:** 02-settings-api-keys
**Areas discussed:** Settings UI structure, API key masked display, CSV export/import scope, Post-action behavior

---

## Settings UI Structure

| Option | Description | Selected |
|--------|-------------|----------|
| Single page, anchor sections | One /settings route with named sections. Reuses AuthCard layout. | ✓ |
| Tabbed interface | Tabs across the top, shadcn-vue Tabs component | |
| Separate sub-routes | /settings/account, /settings/api-keys — sidebar nav | |

**User's choice:** Single page with anchor sections

---

| Option | Description | Selected |
|--------|-------------|----------|
| AppNav — settings icon or link | Gear icon or text in existing AppNav, visible when logged in | ✓ |
| User menu dropdown | Dropdown on email/avatar with Settings + Logout | |
| You decide | Claude picks nav placement | |

**User's choice:** Settings link in AppNav

---

## API Key Masked Display

| Option | Description | Selected |
|--------|-------------|----------|
| Last 4 chars visible | ••••••••••••••••••••a1b2 | |
| All masked, Change button only | Full dot mask, Change button clears field | |
| Status badge + Change button | "Key: Configured ✓", no value shown | |
| **Other (user input)** | **Plaintext — no masking at all. Personal app, same as TMDB/OMDB show the key on their own pages. User wants copy-paste.** | ✓ |

**User's choice:** Plaintext display — overrides SET-01 "always masked" wording. AES-256-GCM encryption at rest unchanged.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — test the key on save | Call TMDB /configuration or OMDB test query before storing | ✓ |
| No — just store it | Accept any non-empty string | |
| You decide | Claude picks | |

**User's choice:** Validate against real API on save

---

## CSV Export/Import Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Backup & restore my own data | Full movie snapshot, re-importable | |
| Migration from another tool | Flexible column mapping (Letterboxd etc.) | |
| Both | Own data + external tool import | |
| **Other (user input)** | **Defer implementation to after Phase 3. Movie schema not stable yet. Add UI placeholder in Phase 2 only.** | ✓ |

**User's choice:** Deferred. Phase 2 = UI placeholder only. SET-05/SET-06 implementation after Phase 3.

---

## Post-action Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Logout immediately, redirect to /login | All tokens revoked, clean redirect with message | ✓ |
| Show message, then redirect after delay | 3-second countdown before logout | |
| Stay logged in (re-issue token) | Revoke others, keep current session | |

**User's choice:** Immediate logout + redirect to /login after password change

---

| Option | Description | Selected |
|--------|-------------|----------|
| Inline success state on the field | Checkmark / "Saved" text on the key input | ✓ |
| Toast notification | Toast top-right | |
| You decide | Claude picks | |

**User's choice:** Inline success state on API key field

---

| Option | Description | Selected |
|--------|-------------|----------|
| Stay on settings, show inline 'Check your inbox' message | Inline message below email field, no redirect | ✓ |
| Redirect to verify-email-sent page | Reuse existing verify-email-sent.vue | |
| You decide | Claude picks | |

**User's choice:** Stay on settings page with inline "Check your inbox" message after email change request

---

## Claude's Discretion

- Exact visual layout and spacing of settings sections
- Whether CSV placeholder shows "coming soon" badge or disabled buttons
- Exact validation error message wording
- Flyway migration version number for user_api_keys table

## Deferred Ideas

- SET-05 CSV Export — after Phase 3 (movie schema not yet stable)
- SET-06 CSV Import — after Phase 3
- Letterboxd import — v2 scope (FEAT-V2-01)
