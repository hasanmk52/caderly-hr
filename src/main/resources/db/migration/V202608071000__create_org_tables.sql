-- Organization tables (PRD §21, sub-phase 1.3). Both tenant-scoped: tenant_id NOT NULL
-- plus the RLS template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).

CREATE TABLE division (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  name varchar(150) NOT NULL,
  description text,
  archived boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE department (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  division_id uuid NOT NULL REFERENCES division (id),
  name varchar(150) NOT NULL,
  description text,
  -- No FK yet: `employee` doesn't exist until Phase 1.4. Plain UUID column, populated
  -- nowhere in 1.3 — reserved so the 1.4 migration only needs to ADD CONSTRAINT.
  head_employee_id uuid,
  archived boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE INDEX idx_department_division_id ON department (division_id);

-- ============================================================================
-- RLS — the template block from V202607241000, applied to each table above.
-- FORCE is critical: the app role OWNS these tables, and Postgres exempts table
-- owners from RLS unless it is forced.
-- current_setting(..., true) returns NULL when app.tenant_id is unset, so each
-- policy denies ALL rows by default — no tenant context means no data.
-- ============================================================================

ALTER TABLE division ENABLE ROW LEVEL SECURITY;
ALTER TABLE division FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON division
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE department ENABLE ROW LEVEL SECURITY;
ALTER TABLE department FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON department
  USING (tenant_id::text = current_setting('app.tenant_id', true));
