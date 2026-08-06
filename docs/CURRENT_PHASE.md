# Current Sub-Phase

**Working on:** Phase 1.3 — Organization: Divisions & Departments
**Branch:** `phase-1.3-org`
**Goal:** An Admin can create, rename, and archive Divisions and Departments, and assign each Department to exactly one Division. Two explicit tables, two levels, no deeper hierarchy exposed. First sub-phase where the UI does htmx-driven row updates and an Alpine offcanvas form.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.3 Organization — Divisions & Departments" section under Phase 1 — MVP
2. `docs/Helyx_PRD.md` — §13 (all four subsections — §13.4 on the N-level migration path is a design constraint, not background), §6.4 (FR-4.x), §21 (`division`, `department` schemas), §26 (permissions matrix — CRUD divisions/departments is Admin-only), §10 for the acceptance-criteria format
3. `CLAUDE.md` — §5 (multi-tenancy contract — both new tables are tenant-scoped), §6 (A01 access control, A03 injection), §8 (testing rules), §10 (the "adding a new tenant-scoped entity" recipe, which now has three worked examples in `identity` to copy)
4. `docs/UI_Guidelines.md` — §6 (tables, forms, slide-over panels), §7.1 (empty states), §7.5 (destructive confirmations), §13 (htmx conventions), §14 (Alpine.js conventions)
5. `docs/adr/0003`, `0004`, `0005` — tenancy mechanics and the tenant-scoped-vs-system-scoped distinction. Do not re-litigate them.

## Already in place — do not redo

- **Everything from 1.1** (see git history): `TenantAwareEntity`, `TenantContext`, `TenantResolutionFilter`, `TenantIdentifierResolver`, `TenantSessionVariableListener`, the RLS template.
- **Identity and auth (1.2):** `app_user` / `user_role` / `password_reset_token`, `AppUserDetailsService`, `InviteService`, `PasswordResetService`, lockout, rate limiting, session management.
- **`SecurityConfig` is now real** — form login, BCrypt 12, `RoleHierarchy` (ADMIN > MANAGER > EMPLOYEE), CSRF on, PRD §19.6 headers, default-deny. It is still on the CLAUDE.md §12 ask-first list; 1.3 should need no change to it beyond nothing at all, since `/admin/**` is already authenticated and `@PreAuthorize` does the rest.
- **Error pages, all four:** `templates/error/` has `403`, `404`, `4xx`, `5xx`, and `spring.web.error.*` is pinned to `never` so nothing leaks a stack trace. `ErrorPageResolutionTest` fails the build if a status stops resolving or the properties drift. If you add a status with wording worth its own page, add `error/<status>.html` — do **not** remove the `4xx`/`5xx` catch-alls.
- **Exception hierarchy + RFC 7807:** `common.HelyxException` with `NotFoundException` / `ValidationException` / `ConflictException`, and `web.GlobalExceptionHandler`, which content-negotiates between a rendered error page and a problem document. Throw these rather than inventing new ones.
- **Email outbox (CLAUDE.md §6a):** `EmailOutboxService.enqueue(...)` with `MANDATORY` propagation, `EmailDispatcher` on a 30s poll. 1.3 sends no email, but any future side effect goes through this.
- **UI shell:** `layout.html` (`layout(title, content)`), `bare-layout.html` for unauthenticated pages, `fragments/head.html` as `head(title)`, a working avatar dropdown with CSRF logout, and `sec:authorize`-gated Admin nav — **confirmed working** against Spring Security 7, so the `thymeleaf-extras-springsecurity6` artifact name is not a problem.
- **Test scaffolding:** `TenantIsolationTestBase`, `MutableClock` + `MutableClockConfiguration` (advance time instead of sleeping), `support/` helpers, the `rls_probe` raw-JDBC pattern, and the `test` Spring profile (`application-test.yml`) which disables the outbox dispatcher.
- **ArchUnit now enforces four rules:** no package cycles, tenant-scoped entities extend `TenantAwareEntity` (exempting `..system..`), **every request-mapping method has `@PreAuthorize`**, and native queries are confined to repositories. Plus `NoSqlConcatenationTest` greps for concatenated queries.
- **Dev bootstrap:** `bootstrap.DevDataSeeder` (`@Profile("dev")`) seeds tenant `mhz` and `admin@mhz.test` / `DevAdmin12345`. Delete it when the Super Admin console lands in 1.13.
- **Dev mail inbox:** `docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit`, read at `http://localhost:8025`.
- **Quality gates:** JaCoCo report (74.8% line, target 0.70, still unenforced), PMD (report-only, currently zero violations — keep it there), OWASP dependency-check (CI-only), ArchUnit. SpotBugs still unbound — its bundled ASM cannot parse Java 25 bytecode.
- **The build now really targets Java 25.** `java.version=25` drives `maven.compiler.release`; the old `source`/`target` properties were silently overridden by the parent's default of 17, so Java 18+ APIs were unavailable. They work now.

## Remaining Phase 1.3 work

### Schema + entities (`org` package)

- Flyway migration for `division` and `department` per PRD §21. Both tenant-scoped: `tenant_id uuid NOT NULL` plus the RLS template block (`ENABLE` + **`FORCE`** + `tenant_isolation`), and a `GRANT SELECT ... TO rls_probe` line in the test migration if you want the raw-JDBC proof.
- Entities extend `TenantAwareEntity`, private setters, intent-named methods (`rename(...)`, `archive()`, `moveToDivision(...)`).
- `department.head_employee_id` stays a nullable plain UUID with **no FK** until `employee` exists in 1.4. Say so in a comment so it does not read as an oversight.
- `UNIQUE (tenant_id, name)` on both, per PRD §21.
- **§13.4 constraint:** two explicit tables, but do not expose a deeper hierarchy in the API or the URLs. Keep the migration to `org_unit` cheap later.

### Backend (`org` package)

- `DivisionService` / `DepartmentService`, `@Transactional` on writes.
- Delete rules (PRD §13.2): hard delete only when nothing references the row; otherwise `archived = true`. In 1.3 there are no employees yet, so the "no active employees" half of the rule cannot be enforced — **decide at plan time** whether to build the check now against an `OrgFacade` seam or defer it to 1.4, and write down which.
- `/admin/divisions` and `/admin/departments` MVC controllers, `@PreAuthorize("hasRole('ADMIN')")` on every method (ArchUnit will fail the build otherwise).
- DTOs as records. Never expose entities to templates.

### Frontend

- Admin console pages: Bootstrap tables per UI Guidelines §6 (`table table-hover align-middle`, `table-responsive`, `<th scope="col">`, empty-state block instead of an empty tbody).
- Slide-over create/edit form (Bootstrap offcanvas, Alpine) and htmx-powered row updates.
- **Watch the CSP.** `SecurityConfig` deliberately omits `'unsafe-eval'`, which Alpine.js needs for expression evaluation, because nothing used Alpine until now. The first `x-data` on a page will break in the browser console. The fix is Alpine's CSP build, **not** loosening the directive — and that is a `SecurityConfig` change, so it is on the §12 ask-first list.

### Tests

- CRUD integration tests per service, happy path plus at least one sad path.
- RBAC: one 200 and one 403 per endpoint per role (Employee and Manager both get 403 on `/admin/divisions` and `/admin/departments`). **Assert the rendered page, not only the status.** 1.2's RBAC tests passed on the status code while the 403 body was Boot's Whitelabel page printing a full stack trace; that is exactly the gap a status-only assertion leaves open.
- Tenant isolation: a `TenantIsolationTestBase` subclass per new entity proving cross-tenant reads return empty (CLAUDE.md §5 rule 8).
- Archive-instead-of-delete behaviour.
- ArchUnit stays green with no new exemptions.

## Definition of Done for Phase 1.3

- Admin can add a Division, add a Department, and assign the Department to that Division.
- A Department that cannot be hard-deleted is archived instead, and archived rows are excluded from the default list.
- Renaming works and preserves the row's identity.
- `/admin/divisions` and `/admin/departments` return 403 for Employee and for Manager.
- Every new entity has a tenant-isolation test proving cross-tenant reads return empty.
- Both new tables have `ENABLE` + `FORCE ROW LEVEL SECURITY` + the `tenant_isolation` policy.
- `./mvnw verify` green with the same gates as 1.2, and PMD still at zero violations.

## Not in scope for Phase 1.3 — do not start any of this

- Employee entity, and therefore the real `head_employee_id` FK and the "no active employees" delete guard — Phase 1.4
- Bulk reassignment of employees between departments — needs employees, Phase 1.4
- Org chart tree view — Phase 2 (PRD §13.3)
- N-level hierarchy / `org_unit` — explicitly deferred (PRD §13.4)
- MFA / TOTP — Phase 1.5
- Tenant-branded email templates and the outbox Admin retry UI — Phase 1.10
- `audit_entry` / `login_audit` / `AuditListener` — Phase 1.11. Note PRD §13.2 says renames are audit-logged; that wiring lands in 1.11, not here.
- Super Admin console — Phase 1.13

## Carried forward from 1.2 — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Playwright E2E is not set up.** Deferred from 1.2 with approval — the invite→login flow is covered by MockMvc, and 1.4 is the first phase with enough screens to justify the harness. Revisit there.
- **Lockout is keyed on the user, not (email + IP)** as PRD §19.1 specifies. Blocked on `login_audit`, which Phase 1.11 owns. The 10/min/IP rate limit covers the IP axis meanwhile. See ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only, not constant-time.** ADR 0006 decision E.
- **Session revocation is wired for password change only.** Role change and termination reuse `SessionRevoker` when those features land (1.3 role editing is not in scope; termination is 1.4, BR-11).
- **The password policy has no common-password blocklist**, contrary to PRD §19.1. Composition rules (≥10 chars, upper + lower + digit) are enforced and tested; the hand-written 134-entry list that originally shipped was removed as theatre. Pick either the HaveIBeenPwned checker or a real breach corpus when it matters — ADR 0006 decision F has the analysis.
- **The tenant primary colour is not yet injected into `--bs-primary`.** `helyx.css` still hardcodes a placeholder, contrary to UI Guidelines §2/§12. Phase 1.10 owns tenant branding.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to Phase 1.4 (this file's 1.2 → 1.3 update is the template).
3. Commit `phase-1.3-org` and open a PR against `main`.
4. Do not start Phase 1.4 in the same session.
