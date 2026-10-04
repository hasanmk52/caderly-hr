-- Per-user Home dashboard layout (ADR 0021, PRD §24.2). One row per user; no row means the
-- registry default. Keyed on app_user, not employee, so an account with no Employee record works.
-- widget_order / hidden_widgets hold comma-separated DashboardWidget keys — at most six short
-- values, never queried by content. Tenant-scoped: tenant_id NOT NULL plus the RLS template block
-- from V202607241000 (CLAUDE.md §5 rules 1 and 2).

CREATE TABLE dashboard_layout (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  user_id uuid NOT NULL UNIQUE REFERENCES app_user (id),
  widget_order varchar(500) NOT NULL,
  hidden_widgets varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE dashboard_layout ENABLE ROW LEVEL SECURITY;
ALTER TABLE dashboard_layout FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dashboard_layout
  USING (tenant_id::text = current_setting('app.tenant_id', true));
