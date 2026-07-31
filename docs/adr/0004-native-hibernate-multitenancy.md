# 0004 — Native Hibernate multi-tenancy replaces custom `@Filter` + AOP

## Status

Accepted. Supersedes the `@Filter`/AOP-specific bullets of ADR 0003 (the RLS, FORCE ROW LEVEL SECURITY, default-deny, and Caffeine-caching decisions in ADR 0003 are unaffected and still in force).

## Context

Phase 1.1's original enforcement mechanism was a hand-written `@FilterDef`/`@Filter` pair on `TenantAwareEntity`, a `@PrePersist` `TenantAssignmentListener`, and a custom `@Aspect` (`TenantEnforcementAspect`) wrapping every `@Service` method to arm the filter and run the RLS `SET LOCAL`. This had two real costs:

- The aspect had to be pinned at `@Order(100)`, deliberately placed inside `@EnableTransactionManagement(order = 0)`, so its `SET LOCAL` bound to the transactional connection rather than a throwaway one — a subtle ordering dependency.
- It only fired on classes annotated `@Service`, matched by a string AOP pointcut — a future class doing tenant-scoped work under a different stereotype would silently skip enforcement.
- Hibernate's `@Filter` mechanism does not apply to `EntityManager.find(id)` or `@ManyToOne` lazy loads (documented in ADR 0003) — a known gap that only RLS covered.

The project actually resolves Hibernate ORM 7.4.1 and Spring Framework 7.0.8 (via the Spring Boot 4.1 BOM) — later than CLAUDE.md previously stated — and both versions ship native mechanisms for exactly this problem.

## Decision

- **`@TenantId` (Hibernate discriminator multi-tenancy)** replaces `@FilterDef`/`@Filter`/`@EntityListeners(TenantAssignmentListener.class)` on `TenantAwareEntity`. `@TenantId` is bound by Hibernate's `TenantIdBinder` into an implicitly-created filter (`_tenantId`) that Hibernate **arms itself** from a `CurrentTenantIdentifierResolver` — no `session.enableFilter(...)` call needed anywhere in application code. Because Hibernate arms it internally rather than the application opting in per query, it applies to every query Hibernate generates for the entity, including `find(id)` — closing the gap noted above. `TenantIdGeneration` auto-populates the column on insert, so `TenantAssignmentListener` is deleted outright.
- **`TenantIdentifierResolver`** (a `CurrentTenantIdentifierResolver<UUID>` bean) is the single place Hibernate asks "what's the current tenant?" For a real request it delegates to `TenantContext.require()`, preserving the fail-closed contract. For `TenantContext.runAsSystem(...)` it returns a fixed `NO_TENANT` sentinel (a nil UUID no real tenant — generated as `UuidGenerator.Style.VERSION_7` — can ever have) instead of throwing; see the "Hibernate resolves a tenant for every session" finding below for why a hard throw isn't viable here.
- **`TenantSessionVariableListener`**, a Spring `TransactionExecutionListener`, replaces the RLS half of `TenantEnforcementAspect`. Its `afterBegin` callback runs the same parameterized `SELECT set_config('app.tenant_id', ?, true)` the aspect ran. It is a plain `@Component` with no registration code: Spring Boot 4.1's transaction-manager autoconfiguration (`TransactionManagerCustomizationAutoConfiguration`) discovers every `TransactionExecutionListener` bean and registers it on the transaction manager itself — manually injecting `PlatformTransactionManager` into the listener's constructor to call `addListener(this)` creates a circular dependency (the transaction manager's own construction needs the listener list first), confirmed by running the isolation suite.
- `TenantEnforcementAspect` and `TenantConfig`'s `@EnableTransactionManagement(order = 0)` are deleted; ordering is no longer a concern once there's no competing aspect to sequence against the transaction advisor.
- Everything else from ADR 0003 — FORCE ROW LEVEL SECURITY, default-deny via `current_setting(..., true)`, `Tenant` not extending `common.BaseEntity`, Caffeine tenant-lookup caching — is unchanged.

## Finding: Hibernate resolves a tenant identifier for every session, unconditionally

This surfaced empirically, not from documentation: once `CurrentTenantIdentifierResolver` is configured, Hibernate calls it for **every** `EntityManager`/session it opens — including ones Spring Data JPA opens at application-startup time to validate repository query metadata, for every repository, not just `@TenantId`-bearing ones. A resolver that throws when no tenant is set (the original design) broke application startup itself: `TenantRepository` — a plain repository over the cross-tenant `tenant` table — failed to bootstrap with `IllegalStateException: No tenant in context`, because Spring Data validates its query before any request or `runAsSystem` call has ever happened.

Two changes together resolve this without weakening fail-closed behavior:

1. **`spring.data.jpa.repositories.bootstrap-mode: lazy`** (`application.yml`) defers each repository's first real touch — including Spring Data's query validation — to its first actual invocation, instead of eager validation at context-refresh time. `TenantRepository`'s first real use is inside `TenantService#bySlug`'s `TenantContext.runAsSystem(...)` block, so by the time Hibernate needs to resolve a tenant, system mode is already set.
2. **`TenantIdentifierResolver` returns `NO_TENANT` for system mode instead of throwing.** This is what makes (1) work at all — `runAsSystem` blocks need *some* identifier for Hibernate to open a session with. It does not weaken CLAUDE.md §5 rule 6:
   - Reads against `@TenantId` entities filtered to `tenant_id = NO_TENANT` return zero rows.
   - Writes are rejected by Postgres itself: the RLS policy template (ADR 0003) has no explicit `WITH CHECK`, so Postgres reuses `USING` for both reads and writes — an INSERT with `tenant_id = NO_TENANT` is aborted unless `app.tenant_id` happens to equal it, which it never will. In practice the foreign key from `tenant_id` to `tenant.id` rejects it even earlier, before RLS is reached.
   - A genuinely forgotten tenant (not `runAsSystem`, no tenant set) still throws — `resolveCurrentTenantIdentifier()` calls `TenantContext.require()` in that case, same as before.

One consequence: `TenantService#bySlug` and any test fixture that calls `TenantRepository` directly (`TenantIsolationTestBase#seedTenant`, `TenantResolutionFilterTest#seedTenantIfAbsent`) must wrap the call in `TenantContext.runAsSystem(...)` explicitly, rather than being implicitly exempted by living in the `tenant` package (the old aspect's pointcut did this invisibly). This is arguably more correct: it's now an explicit, audited declaration at the call site (CLAUDE.md §5 rule 6) instead of an implicit package-based exemption with no audit trail. `TenantService#bySlug` also had to drop its own `@Transactional` annotation — with it, the transaction (and Hibernate's session-creation-time resolver call) began *before* the method body's `runAsSystem(...)` call could set system mode; removing it is safe because `TenantRepository`'s generated implementation already opens its own short transaction per call.

## Finding: `runAsSystem` no longer sees cross-tenant rows in tests, and that's correct

The old `TenantIsolationTest.findAll_inSystemMode_skipsTenantFilterAndSeesAllTenants` expected system-mode reads to return rows from every tenant, because the old aspect explicitly skipped the Hibernate filter in system mode and the Testcontainers datasource user is a superuser (bypasses RLS). Under native `@TenantId`, Hibernate arms the filter automatically and unconditionally — there's no equivalent "skip it for this session" hook (`CurrentTenantIdentifierResolver.isRoot()` exists, but governs whether an already-assigned tenant id is allowed to differ from the session's tenant at insert time, not query-side filtering, per the Hibernate source reviewed). System mode now gets filtered to `tenant_id = NO_TENANT` like any other session, returning zero rows.

This is a test-only behavior change, not a capability loss: real dev/prod already returned zero rows for unscoped system-mode reads via RLS (ADR 0003) — the old test only saw "all tenants" because of the Testcontainers-superuser artifact. The test was renamed to `findAll_inSystemMode_seesNoTenantsRows` and now asserts the same (empty) behavior tests already relied on being true in production. Real cross-tenant reads remain deferred to whenever the Super Admin console needs a deliberate `BYPASSRLS` strategy (ADR 0003, Phase 1.13) — unchanged by this ADR.

## Consequences

- The `find(id)` / `@ManyToOne` gap documented in ADR 0003 is closed for `find(id)` as a direct improvement of this change (proven by a dedicated regression test).
- One fewer runtime dependency's behavior to reason about: no `@Aspect`, no pointcut, no manual `session.enableFilter(...)` call anywhere in application code.
- `TenantIdentifierResolver` and `TenantSessionVariableListener` now carry the same "stop and ask before changing" weight `TenantAssignmentListener` used to (CLAUDE.md §12).
- Any future code that queries `TenantRepository` (or any other repository over a non-`@TenantId` cross-tenant table) directly, outside a real tenant request, must wrap the call in `TenantContext.runAsSystem(...)` explicitly — there is no more implicit package-based exemption.
