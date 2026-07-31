# Current Sub-Phase

**Working on:** Phase 1.1 — Multi-tenancy foundation
**Branch:** `phase-1.1-multitenancy`
**Goal:** Tenant subdomain resolution, `TenantContext`, Hibernate tenant filter, and the base entity plumbing (`BaseEntity` / `TenantAwareEntity`) that every future entity builds on. Any new entity should require zero extra code to be tenant-safe once it extends `TenantAwareEntity`. No employee/leave/org business logic yet.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.1 Multi-tenancy foundation" section under Phase 1 — MVP
2. `docs/Helyx_PRD.md` — §6.2 (Multi-Tenancy functional requirements), §20 (Multi-Tenancy Design — the full model: resolution, enforcement layers, cross-tenant/system bypass, suspension), §21 (`tenant` table schema, under "Core cross-tenant")
3. `CLAUDE.md` — §5 (Multi-tenancy contract, non-negotiable — this phase exists to make every rule in it true by construction) and §8 (testing rules, especially `TenantIsolationTestBase`)
4. `docs/UI_Guidelines.md` — only as much as needed for the one frontend touchpoint this phase has (home page showing tenant name)

## Already in place — do not redo

- Full Phase 0 skeleton: Spring Boot 4.1 app, Java 25, `dev` profile against host Postgres, `helyx_hr` schema wired through Flyway + Hibernate.
- Base UI shell (`layout.html`, `head`/`topbar`/`sidebar` fragments, `helyx.css`) and `HomeController` rendering "Hello from Helyx" at `/`.
- Minimal `SecurityConfig` permitting `/`, static/webjar assets, and `/actuator/health` — no login yet, that's Phase 1.2. Extend this file's `authorizeHttpRequests`, don't replace its Phase 0 behavior until 1.2 actually adds auth.
- Quality gates wired: JaCoCo (report, unenforced), PMD (report-only), ArchUnit (one cycle-freedom test), OWASP dependency-check (CI-only). Formatting is IntelliJ's default Java style, pinned via root `.editorconfig`, not a CLI/CI-enforced reformatter (Spotless + Google Java Format was dropped — CLAUDE.md §7). SpotBugs is configured but **not** bound to `verify` — its bundled ASM can't parse Java 25 bytecode yet (`Unsupported class file major version 69`); re-bind the execution in `pom.xml` once a compatible release ships, don't just re-enable it and hope.
- CI (`.github/workflows/ci.yml`), README, two ADRs (`0001-modular-monolith-multi-tenancy.md`, `0002-server-rendered-ui.md`).
- `com.helyx.helyxhr.web` and `com.helyx.helyxhr.security` packages exist with `HomeController` and `SecurityConfig` respectively.

## Remaining Phase 1.1 work

Group these in your plan-mode plan however makes sense. Do them all before closing this sub-phase.

### Base entity plumbing (`common` package)
- `common.BaseEntity` — mapped superclass with `id` (UUID), `created_at`, `updated_at`.
- `common.TenantAwareEntity extends BaseEntity` — adds `tenant_id UUID NOT NULL`.
- No entity in a tenant-scoped package may skip this — CLAUDE.md §5 rule 1 and 7.

### Tenant + system tables
- Flyway migration creating `tenant` (per PRD §21 schema: `slug`, `name`, `logo_url`, `primary_color`, `timezone`, `locale`, `weekend_days`, `suspended`, `created_at`, `deleted_at`) and `super_admin`.
- Postgres RLS policy template: `USING (tenant_id::text = current_setting('app.tenant_id', true))`, ready to attach to every tenant-scoped table added from here on.

### Tenant resolution
- `tenant.TenantContext` — `ThreadLocal<UUID>`, `try { set } finally { clear }` pattern (CLAUDE.md §5 rule 3). Include `TenantContext.runAsSystem(...)` for the Super Admin/system-job bypass (rule 6), audited.
- `tenant.TenantResolutionFilter` (Spring `Filter`, first in chain) — resolves tenant from the `Host` header subdomain, looks up the tenant row, caches the lookup in Caffeine, populates `TenantContext`. 404 on unknown tenant, 503 on suspended (PRD §20.3).

### Enforcement (defense in depth, PRD §20.4)
- `@TenantId` on `TenantAwareEntity`'s `tenant_id` field (Hibernate 7 discriminator multi-tenancy) — Hibernate arms the filter itself and auto-populates the column on insert; no hand-written `@Filter`/`@FilterDef` or `@PrePersist` listener (CLAUDE.md §5 rules 4 and 5).
- `TenantIdentifierResolver` — a `CurrentTenantIdentifierResolver` bean that resolves the current tenant from `TenantContext` for every session Hibernate opens.
- `TenantSessionVariableListener` — a Spring `TransactionExecutionListener` registered on the transaction manager that runs `SET LOCAL app.tenant_id = ?` in `afterBegin`, so RLS is the backstop even if the ORM-level restriction is ever disabled (CLAUDE.md §5 rule 4a). Not an AOP aspect — no `@Order` coordination with `@EnableTransactionManagement` needed.

### Frontend touchpoint
- Home page shows `{tenant.name}` sourced from `TenantContext`, proving resolution actually works end to end.

### Tests
- `ArchitectureTest`: every `@Entity` in a tenant-scoped package must extend `TenantAwareEntity` (CLAUDE.md §5 rule 7, §8).
- `TenantIsolationTestBase` (CLAUDE.md §8): seed tenant A + tenant B each with an entity; request/read as tenant A must return only A's rows; prove RLS blocks cross-tenant reads even with the Hibernate filter disabled.
- Integration test for `TenantResolutionFilter`: known subdomain resolves, unknown subdomain 404s, suspended tenant 503s.

## Definition of Done for Phase 1.1

- Any new entity requires zero extra code to be tenant-safe once it extends `TenantAwareEntity` — filter, RLS, and `tenant_id` assignment all happen by construction.
- ArchUnit test fails the build if a non-tenant-aware `@Entity` is added to a tenant-scoped package.
- Two-tenant integration test proves cross-tenant reads return empty, with RLS as the enforced backstop, not just the ORM filter.
- Home page shows the resolved tenant's name when hit via its subdomain locally (e.g. `mhz.localhost:8080`).
- `./mvnw verify` still green with the same gates as Phase 0.

## Not in scope for Phase 1.1 — do not start any of this

- Authentication, users, login page, sessions, RBAC — Phase 1.2
- Divisions/Departments, Employee CRUD, or any other domain entity — Phase 1.3+
- Super Admin console UI (the `runAsSystem` bypass plumbing is in scope; a console to use it is not)
- Docker image / Docker Compose — Phase 1.14

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to Phase 1.2 (see the pattern above — this file's Phase 0 → Phase 1.1 update is the template).
3. Commit `phase-1.1-multitenancy` and open a PR against `main`.
4. Do not start Phase 1.2 in the same session.
