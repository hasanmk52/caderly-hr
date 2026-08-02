-- Identity tables (PRD §21, sub-phase 1.2). All three are tenant-scoped: tenant_id NOT NULL
-- plus the RLS template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).

CREATE TABLE app_user (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  email varchar(255) NOT NULL,
  -- Nullable until the invite is accepted: an INVITED user has no password yet.
  password_hash varchar(100),
  -- MFA columns ship unused; Phase 1.5 fills them (PRD FR-1.5).
  mfa_secret varchar(100),
  mfa_enabled boolean NOT NULL DEFAULT false,
  status varchar(20) NOT NULL, -- INVITED, ACTIVE, LOCKED, DISABLED
  last_login_at timestamptz,
  failed_login_count int NOT NULL DEFAULT 0,
  -- Not in PRD §21. Required to honour "5 failures WITHIN 15 min" (PRD §19.1) rather than
  -- "5 failures ever": the counter alone cannot distinguish a burst from five typos spread
  -- over a month. Phase 1.11's login_audit will carry the per-attempt record (and the IP
  -- dimension this phase cannot cover); until then this is the window marker. See ADR 0006.
  failed_login_window_start timestamptz,
  locked_until timestamptz,
  -- SHA-256 hex of the raw 32-byte invite token; the raw token exists only in the email
  -- (CLAUDE.md §6 A02). 64 chars, so varchar(100) is ample.
  invite_token_hash varchar(100),
  invite_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- BR-12: email is unique per tenant, not globally.
  UNIQUE (tenant_id, email)
);

-- Synthetic UUID PK rather than PRD §21's composite (user_id, role) so the entity can extend
-- TenantAwareEntity like every other tenant-scoped table; the UNIQUE below carries the semantic
-- key the composite PK expressed. See ADR 0005 decision A.
CREATE TABLE user_role (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  role varchar(20) NOT NULL, -- EMPLOYEE, MANAGER, ADMIN
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, role)
);

CREATE INDEX idx_user_role_user_id ON user_role (user_id);

CREATE TABLE password_reset_token (
  id uuid PRIMARY KEY,
  -- tenant_id and the BaseEntity timestamps are additions to PRD §21, required by CLAUDE.md
  -- §5 rule 1. Same additive adjustment ADR 0005 made for user_role.
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  token_hash varchar(100) NOT NULL,
  expires_at timestamptz NOT NULL,
  -- Non-null once redeemed; enforces single use (PRD §19.1).
  used_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Reset lookup is by token hash alone (the tenant comes from the subdomain, and RLS/@TenantId
-- scope the row) — this index is the hot path for the reset-password page.
CREATE INDEX idx_password_reset_token_hash ON password_reset_token (token_hash);

-- ============================================================================
-- RLS — the template block from V202607241000, applied to each table above.
-- FORCE is critical: the app role OWNS these tables, and Postgres exempts table
-- owners from RLS unless it is forced.
-- current_setting(..., true) returns NULL when app.tenant_id is unset, so each
-- policy denies ALL rows by default — no tenant context means no data.
-- ============================================================================

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_user
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE user_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_role FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_role
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON password_reset_token
  USING (tenant_id::text = current_setting('app.tenant_id', true));
