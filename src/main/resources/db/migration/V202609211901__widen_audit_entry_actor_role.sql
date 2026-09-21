-- Phase 1.13: impersonation sessions (identity.ImpersonatedAdminPrincipal) tag audit_entry rows
-- with actor_role = 'SUPERADMIN_IMPERSONATING' (24 chars), which does not fit the existing
-- varchar(20). Widening only, no data loss, no RLS/FK involved.
ALTER TABLE audit_entry ALTER COLUMN actor_role TYPE varchar(30);
