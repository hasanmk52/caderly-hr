-- Core cross-tenant tables (PRD §21). These two tables are the ONLY ones allowed
-- to have no tenant_id and no RLS (PRD FR-2.1).

CREATE TABLE tenant (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug varchar(50) NOT NULL UNIQUE,
  name varchar(200) NOT NULL,
  logo_url varchar(500),
  primary_color varchar(7) DEFAULT '#4f46e5',
  timezone varchar(50) NOT NULL DEFAULT 'UTC',
  locale varchar(10) NOT NULL DEFAULT 'en',
  -- Bitmask of weekend days: Sat=64, Sun=32 -> 96 (PRD §21)
  weekend_days int NOT NULL DEFAULT 96,
  suspended boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE super_admin (
  id uuid PRIMARY KEY,
  email varchar(255) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  mfa_secret varchar(100),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================
-- RLS TEMPLATE — apply verbatim to EVERY tenant-scoped table added from here on
-- (CLAUDE.md §5 rule 2). Copy into each new table's migration:
--
--   ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
--   -- FORCE is critical: the app role OWNS these tables, and Postgres exempts
--   -- table owners from RLS unless it is forced.
--   ALTER TABLE <t> FORCE ROW LEVEL SECURITY;
--   CREATE POLICY tenant_isolation ON <t>
--     USING (tenant_id::text = current_setting('app.tenant_id', true));
--
-- current_setting(..., true) returns NULL when app.tenant_id is unset, so the
-- policy denies ALL rows by default — no tenant context means no data.
-- ============================================================================
