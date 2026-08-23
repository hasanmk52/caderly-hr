-- Leave requests (PRD §21, sub-phase 1.6). Tenant-scoped: tenant_id NOT NULL plus the RLS
-- template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).
--
-- decider_id/cancelled_by reference app_user (the acting login identity), not employee — matches
-- PRD §21's exact DDL.

CREATE TABLE leave_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  leave_type_id uuid NOT NULL REFERENCES leave_type (id),
  start_date date NOT NULL,
  end_date date NOT NULL,
  start_half_day_pm boolean NOT NULL DEFAULT false,
  end_half_day_am boolean NOT NULL DEFAULT false,
  duration_days numeric(5, 2) NOT NULL,
  status varchar(20) NOT NULL, -- PENDING, APPROVED, REJECTED, CANCELLED
  note text,
  submitted_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  decider_id uuid REFERENCES app_user (id),
  decision_note text,
  cancelled_at timestamptz,
  cancelled_by uuid REFERENCES app_user (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_leave_req_emp_period ON leave_request (tenant_id, employee_id, start_date, end_date);
CREATE INDEX idx_leave_req_status ON leave_request (tenant_id, status);

ALTER TABLE leave_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_request
  USING (tenant_id::text = current_setting('app.tenant_id', true));

-- ============================================================================
-- M7 (post-1.5 review): BalanceService.adjustManually could push granted below used while used
-- always stayed zero. This phase is what first increments used (booking approval), so the guard
-- becomes a real backstop starting now. Application-level guard lives in BalanceService; this is
-- the defense-in-depth backstop (CLAUDE.md §5's pattern of app-layer + DB-layer enforcement).
-- ============================================================================

ALTER TABLE leave_balance ADD CONSTRAINT leave_balance_granted_used_check CHECK (granted >= used AND used >= 0);

-- Optimistic locking: concurrent approve/cancel racing on `used` is the first real risk this
-- phase introduces (earlier phases never wrote to a balance row from two concurrent requests).
ALTER TABLE leave_balance ADD COLUMN version bigint NOT NULL DEFAULT 0;
