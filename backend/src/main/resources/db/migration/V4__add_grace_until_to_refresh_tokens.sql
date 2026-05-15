-- Add grace_until to allow concurrent refresh without session drop
-- When rotating a refresh token, set grace_until = now + 5 seconds instead of immediately revoking.
-- A second concurrent request arriving within this window will still be accepted.
ALTER TABLE refresh_tokens ADD COLUMN grace_until TIMESTAMPTZ;
