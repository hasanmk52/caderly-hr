-- Phase 1.13: SuperAdmin (com.caderly.caderlyhr.superadmin) extends common.BaseEntity, which
-- requires an updated_at column (see BaseEntity's @UpdateTimestamp field). super_admin predates
-- BaseEntity's introduction on this table, so this is additive-only, no backfill needed beyond
-- the DEFAULT applying to any pre-existing row.
ALTER TABLE super_admin ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
