-- Audit log (Phase 1.11): every write and every login attempt (PRD §18.1, §21, FR-11.x).
--
-- DELIBERATELY NOT TENANT-SCOPED, same category as email_outbox (ADR 0005 decision B, ADR
-- 0017): no @TenantId, no RLS policy. audit_entry is written from inside JPA flush callbacks
-- that fire for every tenant-scoped entity in the app, and login_audit must record an attempt
-- against an unknown email where no AppUser row (and, for a locked-during-tenant-resolution
-- edge case, potentially no clean tenant context) exists to scope against. tenant_id on both
-- tables is a nullable reference for Admin-viewer filtering, not the tenancy discriminator.
-- Any Admin viewer over these tables must filter by tenant_id explicitly (audit.AuditAdminService
-- / audit.LoginAuditAdminService do this) because the ORM will not do it here.
CREATE TABLE audit_entry (
  id uuid PRIMARY KEY,
  tenant_id uuid REFERENCES tenant (id),
  actor_user_id uuid,
  actor_role varchar(20),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  entity_type varchar(100) NOT NULL,
  entity_id varchar(50),
  action varchar(20) NOT NULL, -- CREATE, UPDATE, DELETE
  before_json jsonb,
  after_json jsonb,
  ip varchar(45),
  user_agent varchar(500),
  request_id varchar(50),
  -- Not in PRD §21: BaseEntity supplies it. Rows are append-only (FR-11.4), so this always
  -- equals created_at, but every other entity in the app carries it and a bare exception here
  -- would be a recurring "why is this one different" question.
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_time ON audit_entry (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_entry (tenant_id, entity_type, entity_id);

CREATE TABLE login_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid REFERENCES tenant (id),
  user_id uuid, -- null if the attempted email matches no account (PRD §18.1)
  occurred_at timestamptz NOT NULL DEFAULT now(),
  email_attempted varchar(255),
  success boolean NOT NULL,
  failure_reason varchar(100),
  ip varchar(45),
  user_agent varchar(500),
  request_id varchar(50),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_audit_tenant_time ON login_audit (tenant_id, occurred_at DESC);

-- Backs the (email + IP) lockout re-key (ADR 0017, superseding ADR 0006 decision B): counts
-- failed attempts for one (tenant, email, ip) triple in the trailing window. Partial on
-- "NOT success" because only failures are ever counted, and this is the query's only access
-- path.
CREATE INDEX idx_login_audit_lockout ON login_audit (tenant_id, email_attempted, ip, occurred_at DESC)
  WHERE NOT success;

-- No ENABLE/FORCE ROW LEVEL SECURITY on either table, and that omission is intentional — see
-- the header comment, and email_outbox's migration for the precedent.
