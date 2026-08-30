-- Per-user iCal feed token (PRD §6.6 FR-6.5, §9.4 US-CAL.3, sub-phase 1.8). A column on app_user,
-- not a new table: strictly 1:1 with the user, no history/expiry to track, mirroring the existing
-- mfa_secret column. Stored raw (not hashed, unlike password_reset_token/invite_token_hash) because
-- Settings -> Calendar integration must redisplay the same URL on every visit — see ADR 0014.
--
-- Inherits app_user's existing RLS policy; no new policy needed for a column addition.

ALTER TABLE app_user ADD COLUMN ical_token varchar(64);

CREATE UNIQUE INDEX app_user_ical_token_key ON app_user (ical_token) WHERE ical_token IS NOT NULL;
