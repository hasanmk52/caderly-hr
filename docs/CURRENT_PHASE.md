# Current Sub-Phase

**Working on:** Phase 1.2 — Authentication & Users
**Branch:** `phase-1.2-auth`
**Goal:** A tenant-scoped user can be invited by email, set a password, log in, reset a forgotten password, and be authorized by role — with the invite and reset emails durable through `email_outbox` from day one. Session management, lockout, and rate limiting included. No employee, org, or leave domain yet.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.2 Authentication & Users" section under Phase 1 — MVP
2. `docs/Helyx_PRD.md` — §6.1 (FR-1.1 … FR-1.8, Identity & Access), §9.1 (US-ID.1/2/4), §19.1 (authentication: BCrypt, tokens, sessions, lockout), §19.2 (authorization), §19.7 (rate limiting), §21 (`app_user`, `user_role`, `password_reset_token`, `email_outbox` schemas), §26 (permissions matrix — the source of truth for every `@PreAuthorize`), §17.3 (email delivery), §20.7 (suspended tenant → login rejected)
3. `CLAUDE.md` — §5 (multi-tenancy contract — every new identity table is tenant-scoped), §6 (A01 access control, A02 crypto, A03 injection, A05 misconfiguration, A07 auth failures), **§6a (durable outbox contract — this phase is where it first ships)**, §8 (testing rules), §12 (ask-first list — `SecurityConfig` is on it and this phase rewrites it)
4. `docs/UI_Guidelines.md` — §2 (color), §6 (buttons, forms, cards), §7.3 (error states), §9 (accessibility) for the four auth pages
5. `docs/adr/0003-tenant-isolation-enforcement.md` and `docs/adr/0004-native-hibernate-multitenancy.md` — every new tenant-scoped entity inherits these mechanics; do not re-litigate them

## Already in place — do not redo

- **Base entity plumbing:** `common.BaseEntity` (id/created_at/updated_at), `common.TenantAwareEntity` (`@TenantId` + `TenantIdGeneration` on `tenant_id`), `common.TenantAwareRepository<T>`. A new entity that extends `TenantAwareEntity` is tenant-safe with **zero** extra code — no `@Filter`, no `@PrePersist`, no manual `tenant_id` in any query.
- **Tenant module:** `TenantContext` (incl. `runAsSystem(reason, action)`), `TenantResolutionFilter` (subdomain → tenant, Caffeine-cached with negative caching, 404 unknown / 503 suspended), `TenantIdentifierResolver`, `TenantSessionVariableListener` (RLS `SET LOCAL` per transaction), `TenantService` / `TenantFacade` / `TenantSummary`.
- **RLS template:** `src/main/resources/db/migration/V202607241000__create_tenant_and_super_admin.sql` ends with the verbatim block (`ENABLE` + `FORCE ROW LEVEL SECURITY` + `tenant_isolation` policy). Copy it into every new tenant-scoped table's migration. `FORCE` is not optional — the app role owns the tables.
- **`SecurityConfig`** is still the Phase-0 placeholder: permits `/`, `/webjars/**`, `/css/**`, `/actuator/health`, `authenticated()` for everything else. Replacing its body is this phase's job — but it is on the CLAUDE.md §12 ask-first list, so agree the shape before writing it.
- **Test scaffolding:** `architecture/ArchitectureTest` (cycle-freedom + "tenant-scoped entities extend `TenantAwareEntity`"), `tenantisolation/TenantIsolationTestBase` (seeds tenants A and B, `asTenant(...)` helpers, `runAsSystem` seeding), the `rls_probe` non-superuser JDBC pattern that proves RLS independently of Hibernate, `TenantResolutionFilterTest`, `TestcontainersConfiguration`.
- **`spring-boot-starter-mail`** and `spring-boot-starter-mail-test` are already on the classpath — `JavaMailSender` needs no new dependency.
- **Load-bearing config, do not "clean up":** `spring.data.jpa.repositories.bootstrap-mode: lazy` (required by `@TenantId`, see ADR 0004) and `helyx.base-domain` (subdomain resolution).
- Quality gates as of Phase 0/1.1: JaCoCo (report, unenforced), PMD (report-only), OWASP dependency-check (CI-only), ArchUnit. SpotBugs is configured but **not** bound to `verify` — its bundled ASM cannot parse Java 25 bytecode (`Unsupported class file major version 69`). Re-bind only when a compatible release ships.
- Small cleanup to fold in: `common.TenantAwareRepository`'s javadoc still cites `TenantEnforcementAspect`, deleted in the ADR 0004 refactor.

## Remaining Phase 1.2 work

Group these in your plan-mode plan however makes sense. Do them all before closing this sub-phase.

### Identity schema + entities (`identity` package)

- Flyway migration for `app_user`, `user_role`, `password_reset_token` per PRD §21. Each is tenant-scoped: `tenant_id uuid NOT NULL` plus the RLS template block.
- Entities extend `TenantAwareEntity`. Private setters, intent-named methods (`lockUntil(...)`, `markInvited()`), never public setters.
- `UserStatus` enum (INVITED, ACTIVE, LOCKED, DISABLED) and `Role` enum (EMPLOYEE, MANAGER, ADMIN).
- Design call to make at plan time: PRD's `user_role` has PK `(user_id, role)` plus a `tenant_id` column — decide `@ElementCollection` on `AppUser` vs. a standalone entity, and justify it.
- **`token_revocation` is not a 1.2 table.** It was a JWT-era artifact; PRD §19.1 now specifies server-side sessions with revocation by deleting sessions from the session store, and §21 has no such schema.

### Email outbox infrastructure (CLAUDE.md §6a — first outbox in the codebase)

- `email_outbox` is **system-scoped infrastructure**, the same category as `audit_entry` / `login_audit`: it does **not** extend `TenantAwareEntity`, has **no** `@TenantId`, and has **no** RLS. Its `tenant_id` is a nullable *reference* column used for branding lookup and Admin filtering — not the tenancy discriminator.
- Entity lives in a `.system` sub-package (`notifications.system`) so the ArchUnit rule excludes it by package pattern, not by an allowlist.
- **Follow-on this requires:** `ArchitectureTest.entities_inTenantScopedPackages_extendTenantAwareEntity` currently exempts only `tenant..`, `superadmin..`, and `common..`. Widen it to exempt `..system..` as well, or `EmailOutbox` fails the build.
- `EmailOutboxService.enqueue(tenantId, to, subject, bodyHtml)` writes the row **in the caller's transaction**. No `mailSender.send()` anywhere in a request path.
- `EmailDispatcher`: `@Scheduled(fixedDelay = 30s)`, runs system-scoped (no `TenantContext`, queries `email_outbox` directly across all tenants), sends via `JavaMailSender`, marks `SENT`; on failure increments `attempts` and sets `next_attempt_at` with backoff 30s → 2m → 10m; after 3 attempts marks `FAILED` with the last error. `warn` on transient failure, `error` on `FAILED`. Never lose the row.
- Inline plain HTML for the two 1.2 emails (invite, password reset). Tenant-branded Thymeleaf templates are 1.10.

### Security (`security` package)

- Form login (Thymeleaf), server-side session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`; 8 h idle / 24 h absolute timeout (PRD §19.1).
- Custom `AuthenticationProvider` that scopes the user lookup to the resolved tenant — a valid email under the wrong subdomain must fail as "user not found", never as a different error. Suspended tenant rejected at login (PRD §20.7).
- BCrypt cost 12. Password policy per §19.1 (min 10, upper+lower+digit, common-password blocklist).
- CSRF on for every state-changing form (Spring Security default — do not disable it for htmx; send the token).
- `RoleHierarchy`: ADMIN > MANAGER > EMPLOYEE. `@PreAuthorize` on every controller method, roles taken from PRD §26.
- Lockout: 5 failures in 15 min per (email + IP), via `failed_login_count` + `locked_until`.
- Rate limits with Bucket4j: login 10/min/IP, password-reset request 3/hour/email.
- Session invalidation on password change and role change via `SessionRegistry`.

### Services

- `InviteService`: create user with `INVITED` status, 32-byte `SecureRandom` token stored **SHA-256-hashed**, 24 h TTL, single use, then `EmailOutboxService.enqueue(...)` in the same transaction.
- `PasswordResetService`: identical token discipline; enumeration-safe response (same page and timing whether or not the email exists).

### Frontend

- Login page (top-center card, logo, email + password, "Forgot password?", "Log in").
- Set-password page (invite acceptance).
- Forgot-password and reset-password pages.
- Avatar menu top-right in the topbar fragment, with Logout.

### Tests

- Auth integration: login happy path; wrong-tenant login → user not found; disabled/locked user rejected; suspended tenant rejected.
- Lockout after 5 failures within the window.
- RBAC: one 200 and one 403 test per protected endpoint per role (`/admin/*` as Employee → 403).
- Tenant isolation: every new entity gets a `TenantIsolationTestBase` test proving cross-tenant reads return empty (CLAUDE.md §5 rule 8).
- ArchUnit: the widened `..system..` exemption, and existing rules still green.
- **Outbox durability:** enqueue in a transaction, kill before dispatch, assert the row survives as `PENDING` and is delivered within one poll cycle on restart.
- **Outbox retry:** stub `JavaMailSender` to fail twice; assert 3rd attempt succeeds with `attempts=3`, `status=SENT`.
- MailHog (or GreenMail) container for the full outbox → SMTP → inbox path.
- Playwright E2E: create user → invite → accept → set password → log in → see home.

### Dependencies to get approved before starting (CLAUDE.md §12)

Each needs a version property in `pom.xml` `<properties>` per §7:

- Bucket4j (rate limiting)
- Playwright-Java (E2E)
- MailHog/GreenMail test container

`spring-boot-starter-mail` is already present — no request needed.

## Definition of Done for Phase 1.2

- Login works on the tenant subdomain; the same email under another tenant's subdomain fails as "user not found".
- An invite triggered in dev lands in the MailHog inbox.
- `SIGKILL` after the enqueue commit but before dispatch loses nothing: on restart the dispatcher delivers within one poll cycle.
- CSRF blocks a form POST without a token.
- `/admin/*` as an Employee returns 403.
- Every new entity has a tenant-isolation test proving cross-tenant reads return empty.
- `./mvnw verify` green with the same gates as Phase 1.1.

## Not in scope for Phase 1.2 — do not start any of this

- MFA / TOTP — Phase 1.5
- Tenant-branded email templates, the full notification event catalog, and the outbox Admin retry UI — Phase 1.10. (CLAUDE.md §6a allows the Admin UI to land one sub-phase later, but it must exist before the outbox reaches production.)
- `audit_entry` / `login_audit` and the `AuditListener` — Phase 1.11. Lockout in this phase uses `app_user.failed_login_count`, not a login-audit table.
- Divisions/Departments — Phase 1.3
- Employee entity and profile — Phase 1.4
- Super Admin console — Phase 1.13
- Docker image / Compose — Phase 1.14

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to Phase 1.3 (this file's 1.1 → 1.2 update is the template).
3. Commit `phase-1.2-auth` and open a PR against `main`.
4. Do not start Phase 1.3 in the same session.
