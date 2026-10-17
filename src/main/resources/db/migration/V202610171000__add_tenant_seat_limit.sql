-- Self-serve workspaces are capped at a number of non-terminated employees (ADR 0022 decision F).
-- Nullable on purpose: NULL means "no limit", which is what every existing tenant and every
-- Super-Admin-created tenant keeps. A NOT NULL DEFAULT would silently cap tenants that were never
-- offered a limit. Additive only; tenant has no tenant_id and no RLS (PRD FR-2.1).
ALTER TABLE tenant ADD COLUMN seat_limit int;
ALTER TABLE tenant ADD CONSTRAINT tenant_seat_limit_positive CHECK (seat_limit IS NULL OR seat_limit > 0);
