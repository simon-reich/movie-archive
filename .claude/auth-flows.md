# Auth Flows

## Sign-Up & Email Verification

1. User submits Sign-Up form (email + password).
2. Backend creates user with `status = PENDING_VERIFICATION`, hashes password with BCrypt (cost 12).
3. Generates `email_verification_token` (single-use, 24h TTL, SHA-256 hash stored), sends `welcome-verify.html` mail.
4. Frontend shows "Check your inbox" page.
5. User clicks link → `POST /auth/verify-email { token }`.
6. Backend validates, sets `status = ACTIVE`, marks token consumed.
7. **Login only possible when `status = ACTIVE`**. Otherwise `403` with resend option.

## Forgot Password / Reset

1. `POST /auth/forgot-password { email }` → always `200 OK` (enumeration protection).
2. If email exists: generate `password_reset_token` (1h TTL), send `password-reset.html`.
3. User submits `POST /auth/reset-password { token, newPassword }`.
4. Backend validates, sets new password, invalidates token, **revokes all refresh tokens**.

## Token Mechanics

- **JWT**: HS256, ~15 min lifetime.
- **Refresh Token**: HttpOnly + Secure + SameSite=Strict cookie, ~7 days. Rotation on every `/auth/refresh` (old revoked, new issued).
- All token tables: store SHA-256 hash only, single-use via `consumed_at`.

---

## Endpoint Reference

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/auth/signup` | POST | – | Create account, send verification mail |
| `/auth/verify-email` | POST | – | Redeem verification token |
| `/auth/resend-verification` | POST | – | New verification link (always 200) |
| `/auth/login` | POST | – | Issue JWT + refresh cookie (ACTIVE only) |
| `/auth/refresh` | POST | Refresh cookie | New tokens with rotation |
| `/auth/logout` | POST | JWT | Revoke refresh token |
| `/auth/forgot-password` | POST | – | Trigger reset mail (always 200) |
| `/auth/reset-password` | POST | – | Redeem token, set new password, revoke all refresh tokens |
| `/auth/me` | GET | JWT | Own user info |

---

## Mail Templates (English, Thymeleaf)

| Template | Trigger | Content |
|---|---|---|
| `welcome-verify.html` | Sign-Up | Welcome + verification link |
| `password-reset.html` | Forgot Password | Reset link + "ignore if not you" |
| `email-changed.html` | Email change in Settings | Confirmation to new address, info to old |

---

## Settings Endpoints (Phase 2)

| Action | Endpoint | Notes |
|---|---|---|
| Save TMDB key | `PUT /settings/api-keys/tmdb` | AES-256-GCM encrypted, response always masked (`••••1234`) |
| Save OMDB key | `PUT /settings/api-keys/omdb` | Optional, same encryption |
| Change email | `POST /settings/email` | Token mail to new address; old address notified |
| Change password | `POST /settings/password` | Requires current password; revokes all refresh tokens |
