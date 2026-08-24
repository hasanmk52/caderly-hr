# 0003 — Tenant isolation enforcement mechanics

## Status

Accepted. The `@Filter`/AOP-specific bullets below (custom `@FilterDef`/`@Filter`, `TenantEnforcementAspect`) are superseded by [ADR 0004](0004-native-hibernate-multitenancy.md); RLS, default-deny, `Tenant` entity shape, and caching decisions here remain in force.

## Context

Phase 1.1 implements the enforcement layers from PRD §20.4 / CLAUDE.md §5. Several mechanics are non-obvious and were decided during implementation; they are recorded here so later phases don't accidentally undo them.

## Decision

- **`FORCE ROW LEVEL SECURITY` on every tenant-scoped table.** The app role (`caderly`) owns the tables, and Postgres exempts table *owners* from RLS unless it is forced. Without FORCE, RLS would be decoration in every environment we run.
- **Default-deny via `current_setting('app.tenant_id', true)`.** Unset setting → NULL → policy matches nothing. No tenant context means no rows, not all rows.
- **One AOP aspect arms both layers.** `TenantEnforcementAspect` (order 100, inside the transaction advisor pinned at order 0) enables the Hibernate `tenantFilter` *and* runs `set_config('app.tenant_id', ?, true)` (= `SET LOCAL`, transaction-scoped, parameterized) on the transactional connection. The tenant module itself is excluded from the pointcut: it only touches cross-tenant tables and must run before a context exists.
- **`Tenant` does not extend `common.BaseEntity`.** `common` depends on `tenant` (`TenantAssignmentListener` → `TenantContext`), so the reverse edge would create a package cycle (ArchUnit-enforced); the `tenant` table also has no `updated_at`/`tenant_id`, so the superclass shape doesn't fit the cross-tenant root anyway.
- **`runAsSystem` is app-layer-only for now.** It skips the Hibernate filter, but the app role remains subject to RLS, so an unscoped system read of tenant-scoped tables returns zero rows in dev/prod. That is safe-by-default and acceptable until the Super Admin console (Phase 1.13) needs real cross-tenant reads — at which point a deliberate `BYPASSRLS`-style strategy gets its own ADR. Note the test-environment caveat: Testcontainers' default user is a superuser (superusers always bypass RLS), so the RLS backstop tests connect via a dedicated non-superuser `rls_probe` role instead.
- **Tenant lookups are cached in Caffeine (60 s TTL, max 1000, misses cached too).** Negative caching stops unknown-subdomain floods from reaching the database. Consequence: new tenants, slug changes, and suspensions take up to 60 s to be seen by the resolution filter.
- **Hibernate filter caveat:** `@Filter` applies to queries, not to `EntityManager.find(id)` — direct-by-id loads are covered by RLS, which is precisely why the second layer exists.

## Consequences

- Every new tenant-scoped table must copy the RLS template from `V202607241000__create_tenant_and_super_admin.sql` verbatim, including FORCE.
- System jobs that need cross-tenant data cannot be written yet; doing so requires the deferred RLS-bypass decision first.
- Suspending a tenant takes effect within the cache TTL, not instantly.
