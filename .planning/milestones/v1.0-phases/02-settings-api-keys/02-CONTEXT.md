# Phase 2: Settings & API Keys - Context

**Gathered:** 2026-05-16
**Status:** Ready for planning

<domain>
## Phase Boundary

Users can configure their TMDB and OMDB API keys, change their password, change their email address, and see a placeholder for future CSV import/export. The settings page is a single `/settings` route accessible from the main nav.

**Scope anchor:** SET-01, SET-02, SET-03, SET-04 from REQUIREMENTS.md.
SET-05 (CSV export) and SET-06 (CSV import) are DEFERRED — placeholder UI section only, no backend implementation. Full implementation after Phase 3 when the movie schema is stable.

</domain>

<decisions>
## Implementation Decisions

### Settings UI Structure
- **D-01:** Single `/settings` page with named anchor sections: **Account** (email change, password change), **API Keys** (TMDB key, OMDB key), **Import & Export** (placeholder only — no backend).
- **D-02:** Settings link appears in the existing **AppNav** component — a gear icon or "Settings" text link, visible when logged in. No dropdown menu.

### API Key Display & Validation
- **D-03:** API keys are displayed in **plaintext** in the settings page — no masking. The backend decrypts the AES-256-GCM-encrypted key on read and returns the full plaintext value. User can directly copy-paste. This overrides the "always masked" wording in SET-01; encryption at rest (AES-256-GCM) is unchanged.
- **D-04:** When saving a new API key, the backend **validates it against the real API** before storing: TMDB key → call TMDB `/3/configuration`, OMDB key → call OMDB with a test query. If the key is rejected by the API, return an error and do not store the key.

### Post-action Behavior
- **D-05:** After a successful **password change**: revoke all refresh tokens (including the current session), then immediately redirect the user to `/login` with an inline message: "Password changed. Please log in again."
- **D-06:** After successfully **saving an API key**: show an **inline success state** on the key input field (checkmark / "Saved" text). No toast, no redirect. Consistent with the inline error pattern from Phase 1.
- **D-07:** After submitting an **email change request**: stay on the settings page and show an inline "Check your inbox — click the link to confirm your new address" message below the email field. No redirect.

### CSV Import & Export
- **D-08:** Phase 2 adds a visually complete **"Import & Export" section** in the settings UI (buttons for Export CSV and Import CSV) but the buttons are either hidden behind a "coming soon" note or disabled. No backend endpoints for CSV in Phase 2.

### Error Handling
- **D-09:** Carry forward from Phase 1: backend error responses use `{"message": "..."}` flat JSON. Frontend reads `.message` directly.
- **D-10:** Carry forward from Phase 1: errors shown as **inline form errors** (not toasts). Success feedback is also inline (D-06, D-07).

### Claude's Discretion
- Exact visual layout and spacing of the settings sections.
- Whether the "Import & Export" placeholder shows a "coming soon" badge or simply disabled buttons.
- Exact wording of validation error messages (e.g., "Invalid TMDB API key — check your key and try again").
- Flyway migration version number for `user_api_keys` table (V5 or next available).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Data Model
- `.claude/data-model.md` — `user_api_keys` table schema (`id`, `user_id` FK, `provider` TMDB/OMDB, `encrypted_key`). `email_change_tokens` table for SET-04. Flyway migration history (V1–V4 applied).

### Auth Flows & Endpoints
- `.claude/auth-flows.md` — `email_change_tokens` flow, token TTL (24h), notification to old address. Password change token mechanics.

### API Contracts
- `.claude/api-contracts.md` — TMDB and OMDB API details needed for key validation on save (which endpoint to call, what response to expect for a valid vs invalid key).

### Requirements
- `.planning/REQUIREMENTS.md` §Settings — SET-01 through SET-06 (SET-05/06 deferred to post-Phase 3).

### Tech Stack Constraints
- `CLAUDE.md` §AES-256-GCM Encryption — IV management, prepend-IV-to-ciphertext pattern, master key from ENV. Decrypt-on-read for plaintext display (D-03).
- `CLAUDE.md` §Spring @Async+@Retryable — WebClient for API validation calls (TMDB/OMDB key test).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/components/AuthCard.vue` — Card wrapper used on all auth pages. Reuse for settings sections.
- `frontend/components/FormField.vue`, `InputText.vue`, `ButtonPrimary.vue`, `FormErrorBanner.vue` — Full form component set from Phase 1. Use directly for settings forms.
- `frontend/composables/useAuth.ts` — Auth composable. Reference for how to call backend and handle errors/redirects.
- `frontend/stores/auth.ts` — Pinia auth store. Will need a companion `useSettings` composable or similar for settings API calls.
- `backend/.../token/` — All token entity/repository patterns established. `EmailChangeToken` entity and repository already exist.
- `backend/.../user/User.java` — Entity to update on password change and email change.

### Established Patterns
- UUID primary keys, Lombok `@Getter/@Setter/@NoArgsConstructor` on entities.
- Spring Data JPA repositories.
- Testcontainers (real Postgres) + WireMock (external APIs) for integration tests.
- Error response: `{"message": "..."}` — flat JSON.
- Frontend: inline error display via `FormErrorBanner` and field-level error state.

### Integration Points
- Flyway: next migration is V5 — creates `user_api_keys` table (`id UUID PK`, `user_id UUID FK → users`, `provider VARCHAR CHECK IN ('TMDB','OMDB')`, `encrypted_key TEXT NOT NULL`, `UNIQUE(user_id, provider)`).
- `SecurityConfig`: new endpoints `/settings/**` must be authenticated (no permitAll).
- `AppNav.vue`: add settings link (gear icon or text) visible when `isLoggedIn` is true in auth store.
- TMDB/OMDB key validation calls: use WireMock stubs in tests (per CLAUDE.md constraint — no real API calls in CI).

</code_context>

<specifics>
## Specific Ideas

- API key display is plaintext intentionally — this is a personal single-user app and the user wants the same copy-paste experience as on TMDB/OMDB's own dashboards (D-03).
- Key validation (D-04) must be fully covered by WireMock stubs in integration tests — one stub for valid key response, one for 401/invalid key response.
- Password change flow (D-05): the current session's refresh token is included in the "revoke all" operation, so the HttpOnly cookie becomes invalid. The Nuxt middleware will catch the next request, but proactively redirect to `/login` from the settings page frontend code is cleaner UX.

</specifics>

<deferred>
## Deferred Ideas

- **SET-05 CSV Export** — Deferred until after Phase 3. Movie schema (all 40+ fields) must be stable before designing the export format. Phase 2 adds UI placeholder only.
- **SET-06 CSV Import** — Same reason. Deferred until after Phase 3.
- **Letterboxd CSV import** — Explicitly v2 in REQUIREMENTS.md (FEAT-V2-01). Not in scope for v1 at all.

</deferred>

---

*Phase: 02-settings-api-keys*
*Context gathered: 2026-05-16*
