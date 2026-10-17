-- A self-serve sign-up waiting for its email to be confirmed (ADR 0022 decision C).
--
-- DELIBERATELY NOT TENANT-SCOPED: no tenant exists until the link is opened, so there is no
-- tenant_id to scope by. Same category as email_outbox (ADR 0005 decision B): system
-- infrastructure, no RLS, entity lives in a .system package. Rows hold only what the visitor typed
-- into the form and are deleted on use or within an hour of expiring.
CREATE TABLE pending_signup (
  id uuid PRIMARY KEY,
  -- One live hold per address: the unique index is the race guard between two simultaneous
  -- sign-ups; the service deletes expired rows for the slug before inserting.
  slug varchar(50) NOT NULL,
  company_name varchar(200) NOT NULL,
  timezone varchar(50) NOT NULL,
  weekend_days int NOT NULL,
  admin_name varchar(200) NOT NULL,
  admin_email varchar(255) NOT NULL,
  -- SHA-256 hex of the raw token (common.SecureToken); the raw token exists only in the email.
  token_hash varchar(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_pending_signup_slug ON pending_signup (slug);
CREATE UNIQUE INDEX ux_pending_signup_token_hash ON pending_signup (token_hash);
CREATE INDEX idx_pending_signup_admin_email ON pending_signup (admin_email);
CREATE INDEX idx_pending_signup_expires_at ON pending_signup (expires_at);

-- No ENABLE/FORCE ROW LEVEL SECURITY here, and that is intentional — see the header comment.
