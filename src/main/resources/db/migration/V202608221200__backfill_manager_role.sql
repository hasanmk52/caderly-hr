-- Retroactive backfill for the manager-role sync gap discovered during Phase 1.6 manual testing
-- (ADR 0011): PRD §5 says MANAGER is derived from the org chart, but before this fix nothing ever
-- granted it. EmployeeService.reassignManagerInternal now keeps user_role in sync going forward
-- (see ManagerRoleSyncEvent/ManagerRoleSyncListener); this migration is the one-time catch-up for
-- org charts that already existed before the fix landed.
--
-- MUST loop per tenant and set_config('app.tenant_id', ..., true) before each tenant's writes.
-- user_role has FORCE ROW LEVEL SECURITY (V202608021000__create_identity_tables.sql), and the
-- role this migration runs as (the app's own DB role in dev/prod, not BYPASSRLS — see
-- src/test/resources/db/migration/V999912312359__create_isolation_probe.sql's comment on why
-- that role is not a superuser) means a single cross-tenant INSERT would have every row's policy
-- check `tenant_id::text = current_setting('app.tenant_id', true)` evaluated against whatever
-- tenant_id (or none) happens to be set — silently inserting far fewer rows than intended, not
-- backfilling every tenant. Do not "simplify" this into one INSERT.
DO $$
DECLARE
  t RECORD;
BEGIN
  FOR t IN SELECT id FROM tenant LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);

    INSERT INTO user_role (id, tenant_id, user_id, role)
    SELECT gen_random_uuid(), m.tenant_id, m.user_id, 'MANAGER'
    FROM employee m
    WHERE m.tenant_id = t.id
      AND m.user_id IS NOT NULL
      AND EXISTS (
        SELECT 1 FROM employee r
        WHERE r.manager_id = m.id
          AND r.status <> 'TERMINATED'
      )
      AND NOT EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.tenant_id = m.tenant_id
          AND ur.user_id = m.user_id
          AND ur.role = 'MANAGER'
      );
  END LOOP;
END $$;
