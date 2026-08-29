-- Company files and employee documents (PRD §6.7, §21, sub-phase 1.7). Tenant-scoped: tenant_id
-- NOT NULL plus the RLS template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).
--
-- No separate created_at/updated_at in PRD §21's own DDL sketch, but every entity extends
-- BaseEntity (CLAUDE.md §5), which requires both — matching every prior migration's actual
-- convention over the PRD's illustrative sketch. uploaded_by references app_user (the acting
-- login identity), not employee, matching PRD §21's exact DDL and leave_request's decider_id/
-- cancelled_by precedent.

CREATE TABLE company_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  name varchar(300) NOT NULL,
  mime varchar(100) NOT NULL,
  size_bytes bigint NOT NULL,
  storage_key varchar(500) NOT NULL,
  uploaded_by uuid NOT NULL REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE employee_document (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  name varchar(300) NOT NULL,
  mime varchar(100) NOT NULL,
  size_bytes bigint NOT NULL,
  storage_key varchar(500) NOT NULL,
  visibility varchar(20) NOT NULL, -- EMPLOYEE_PRIVATE, ADMIN_ONLY
  uploaded_by uuid NOT NULL REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_document_employee_id ON employee_document (tenant_id, employee_id);

-- ============================================================================
-- RLS — the template block from V202607241000, applied to each table above.
-- ============================================================================

ALTER TABLE company_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_file FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON company_file
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE employee_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_document
  USING (tenant_id::text = current_setting('app.tenant_id', true));
