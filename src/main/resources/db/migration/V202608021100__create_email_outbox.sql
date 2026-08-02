-- Durable email outbox (CLAUDE.md §6a, PRD §17.3, §21). The first outbox in the codebase;
-- later outboxes (webhook_outbox in Phase 2) copy this shape.
--
-- DELIBERATELY NOT TENANT-SCOPED. This is system infrastructure, the same category as
-- audit_entry and login_audit: no @TenantId, no RLS policy, and the tenant_id column below
-- is a nullable *reference* used for branding lookup and Admin-viewer filtering, not the
-- tenancy discriminator. The dispatcher polls every tenant's pending mail in one query.
-- See ADR 0005 decision B. Any Admin viewer over this table must filter by tenant_id
-- explicitly, because the ORM will not do it here.
CREATE TABLE email_outbox (
  id uuid PRIMARY KEY,
  -- Nullable: Super Admin and system mail belongs to no tenant.
  tenant_id uuid REFERENCES tenant (id),
  to_email varchar(255) NOT NULL,
  subject varchar(500) NOT NULL,
  body_html text NOT NULL,
  status varchar(20) NOT NULL, -- PENDING, SENT, FAILED
  attempts int NOT NULL DEFAULT 0,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  -- Not in PRD §21: BaseEntity supplies it, and on an outbox it usefully records when the
  -- row's delivery state last changed.
  updated_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  -- Always set on enqueue (to now), never NULL, so the dispatcher's due-row query is a plain
  -- range scan with no OR-IS-NULL branch.
  next_attempt_at timestamptz NOT NULL DEFAULT now()
);

-- The dispatcher's only query: due PENDING rows, oldest first.
CREATE INDEX idx_email_outbox_due ON email_outbox (status, next_attempt_at);

-- No ENABLE/FORCE ROW LEVEL SECURITY here, and that omission is intentional — see the header
-- comment. A future reviewer checking "does every new table have the RLS block?" should find
-- this note rather than assume an oversight.
