# Current Sub-Phase

**Working on:** Phase 1.5 — Leave Types, Holidays, Balances
**Branch:** `phase-1.5-leave-config`
**Goal:** An Admin configures the tenant's leave policy — leave types and the public holiday calendar — and every employee has a `leave_balance` row per active leave type, auto-granted on hire and refreshed by an idempotent nightly/annual job. This phase lays the data foundation Phase 1.6 (booking/approvals) reads and writes; it does not add a booking UI itself.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.5 Leave Types, Holidays, Balances" section under Phase 1 — MVP (distinct from the later, differently-scoped top-level "Phase 1.5 — MFA + Data Import" heading further down the same document — a naming coincidence; this sub-phase is the one Phase 1.4 pointed to)
2. `docs/Helyx_PRD.md` — §12 (Leave Management — Detailed: leave types, accrual/grant rules, pro-rating, §12.3 specifically for the duration algorithm Phase 1.6 will consume but this phase's balance math must already be compatible with), §6.2 (FR-2.x — leave type fields, holiday fields, balance fields), §21 (`leave_type`, `public_holiday`, `leave_balance` schemas), §26 (permissions matrix), §10 for the acceptance-criteria format
3. `CLAUDE.md` — §5 (multi-tenancy contract — every new table is tenant-scoped), §6a (durable outbox — this phase likely adds no new external side effect, but re-confirm before assuming that), §8 (testing rules — grant idempotency and pro-rate math are exactly the "critical logic, TDD first" category), §10 (the "adding a new tenant-scoped entity" recipe)
4. `docs/UI_Guidelines.md` — §6 (tables, forms — Leave Types CRUD grid follows the same list+offcanvas pattern as Organization/People), §7.1 (empty states)
5. `docs/adr/0007` (htmx admin CRUD transaction pattern — copy for `LeaveTypeService`/`PublicHolidayService` admin endpoints), `docs/adr/0005` (system-scoped tables and the `runAsSystem` correction — required reading before writing `BalanceService.grantAnnual`'s scheduled job: it must fan out over real per-tenant contexts via `TenantFacade.listActiveTenantIds()`, exactly like `people.EmployeeTerminationJob` — `runAsSystem` alone reads zero rows for RLS-protected tables, this bit Phase 1.4 once already)

## Already in place — do not redo

- **Everything from 1.1–1.4** (see git history): `TenantAwareEntity`, `TenantContext`, `TenantResolutionFilter`, `TenantIdentifierResolver`, `TenantSessionVariableListener`, the RLS template; full auth flow; `division`/`department` and the Organization admin page; `employee` and its seven sub-entities, the Employee admin CRUD + self-service Profile page, `CryptoConverter` (ADR 0008), the Employee↔AppUser invite-accept event wiring, `EmployeeTerminationJob`.
- **`org` package and `OrgFacade`** — `DivisionService.listActive()`/`DepartmentService.listActive()` plus `OrgFacade.listActiveDepartments()`/`listActiveDivisions()` for any leave-config screen that needs to scope by department (if PRD §12 calls for department-specific leave types — check before assuming tenant-wide-only).
- **`people` package and `PeopleFacade`** — `EmployeeService` for hire-date/status reads (`BalanceService.grantOnHire` needs `hire_date`; the nightly grant needs to iterate active employees). Read `Employee` via `PeopleFacade`-style read access if this phase's `leave` package needs employee data cross-module — **decide whether `leave` needs its own facade contract from `people` now** (this phase is `people`'s first real cross-module consumer beyond `web`), mirroring the `OrgFacade`/`PeopleFacade` split's cycle-avoidance reasoning from 1.4's carried-forward notes below.
- **Cross-tenant scheduled job pattern** — `people.EmployeeTerminationJob` + `tenant.TenantFacade.listActiveTenantIds()` is the reference implementation for `BalanceService.grantAnnual`'s `@Scheduled(cron)` job (PRD says "runs Jan 1"): fan out over `TenantFacade.listActiveTenantIds()`, set a **real** `TenantContext` per iteration — `TenantContext.runAsSystem(...)` alone reads zero rows from RLS-protected tables (ADR 0003), it only bypasses Hibernate's own `@TenantId` filter. This cost real debugging time in 1.4; don't rediscover it.
- **htmx + Alpine, offcanvas-form-plus-table-swap** — `admin/organization.html`/`AdminOrganizationController` and `people/list.html`/`AdminEmployeeController` are both reference implementations now. Copy directly for the Leave Types CRUD grid and Holidays page.
- **Error pages, exception hierarchy, RFC 7807, email outbox, UI shell, test scaffolding, ArchUnit rules, dev bootstrap, quality gates, Playwright-Java (first stood up in 1.4)** — all as documented in prior phases' history. No changes needed. `EmployeeLifecycleE2ETest` is the reference Playwright spec — reuse its login/outbox-token-extraction helpers rather than re-deriving them.

## Remaining Phase 1.5 work

### Schema + entities (new `leave` package, or extend `people` — decide and record which)

- Flyway migration for `leave_type`, `public_holiday`, `leave_balance` per PRD §21. All tenant-scoped: RLS template + `@TenantId`, `rls_probe` grants in the test migration (3 more tables added to that grant list, following the exact pattern `V202608121242`'s people-tables migration and its isolation-probe update used).
- `leave_balance.employee_id` — real FK to `employee` (now exists, unlike Phase 1.3's deferred `head_employee_id`).

### Backend

- `LeaveTypeService` CRUD (mirrors `DivisionService`'s create/edit/archive-or-delete shape where applicable — check PRD §12 for whether Leave Types can be archived or only soft-disabled).
- `PublicHolidayService` CRUD + CSV bulk upload — this is a new pattern (file upload + parse); check whether `FileStorage` (Phase 1.7) is a hard dependency or whether CSV parsing can happen in-request without persisting the uploaded file itself (likely the latter — the CSV is a one-time input, not a stored document).
- `BalanceService.grantAnnual(year)` — **idempotent** (PRD explicit requirement, and this phase's DoD tests it directly): running it twice for the same year must not double-grant. `@Scheduled(cron)` fires Jan 1, following `EmployeeTerminationJob`'s fan-out-over-tenants shape.
- `BalanceService.grantOnHire(employee)` — pro-rates by months remaining in the year. This is exactly the kind of math CLAUDE.md §8 requires TDD for: write the failing test first, across several hire-date fixtures (start of year, mid-year, Dec 31, leap-year edge if relevant).
- Manual adjustment endpoint (Admin, with required reason). "Required reason → audit" — `audit_entry` itself is Phase 1.11 per 1.4's Not-in-scope note; confirm what "audit" means here before 1.11 lands (likely: reason is a required form field and gets logged via SLF4J at minimum, with the real `audit_entry` write added retroactively in 1.11 the same way other phases have deferred it).

### Frontend

- Admin: Leave Types CRUD grid — reuse the list+offcanvas pattern (`people/list.html` is the more recent, more complete reference over `admin/organization.html`).
- Admin: Holidays page with a calendar picker + CSV upload control.
- Employee: Home dashboard "Book Time Off" cards showing balance per type — this reads `leave_balance` rows on the existing (currently mostly-empty) home page; check what's there today before assuming a blank slate.

### Tests

- Grant idempotency: running `grantAnnual(year)` twice produces the same balances, not doubled ones.
- Pro-rate math correctness across hire-date fixtures (see above).
- Tenant isolation test for all 3 new tables (JPA + raw-JDBC `rls_probe`), mirroring `PeopleTenantIsolationTest`.
- RBAC: Admin-only on Leave Types/Holidays CRUD and the manual adjustment endpoint; Employee read-only on their own balance.
- Playwright: extend `EmployeeLifecycleE2ETest`'s pattern, or add a new spec, for "Admin defines a leave type → uploads holidays → employee's balance appears" if screens exist by the time this is written — check DoD wording; a service-level integration test may satisfy the DoD without a browser test if no new employee-facing screen ships this phase beyond the dashboard cards.

## Definition of Done for Phase 1.5

- Admin defines 3 leave types.
- Admin uploads a holiday calendar (e.g. "UAE 2026").
- Employee sees their balances on the home dashboard.
- Running `grantAnnual(2027)` in January produces fresh balances — and running it twice does not double them.
- Every new table: RLS + `@TenantId` + tenant-isolation test.
- `./mvnw verify` green, PMD at zero violations, ArchUnit green with no new exemptions.

## Not in scope for Phase 1.5 — do not start any of this

- Booking, approving, rejecting, or cancelling leave — Phase 1.6 owns `leave_request` and the whole request lifecycle.
- The leave-duration algorithm (PRD §12.3) itself — Phase 1.6.
- `audit_entry` as a real persisted table — Phase 1.11 (see note above on what "→ audit" means until then).
- File storage for the holiday CSV as a retained document — Phase 1.7, unless the CSV is parsed-and-discarded rather than stored (likely the right call; confirm in an ADR only if there's a reason to persist the raw upload).

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.** Phase 1.4 built self/Admin/manager-of-report viewing only (`EmployeeService.getProfileForViewer`); an Employee or Manager cannot yet view an unrelated colleague's basic profile fields. Deferred because 1.4's DoD didn't require it and a "basic fields only" projection is a real design decision (which fields count as "basic"?) worth its own moment rather than a rushed addition. Revisit when a screen actually needs peer browsing (People directory search, org chart, etc.).
- **Termination's "cancel future leave requests" (PRD §14.4) is a no-op.** `EmployeeService.applyTermination` has a one-line comment marking where the call goes once `leave_request` exists — this phase's sibling, 1.6, is what finally closes this gap (not 1.5, since `leave_request` isn't created until 1.6).
- **Employee custom fields** — schema only (`employee_custom_field_definition`/`_value`), no UI. Phase 2, PRD §14.5.
- **Grid/tree People views** — Phase 2, PRD §8.3.
- **Tasks and Time Off tabs on the Profile page are omitted from the nav**, not built as disabled placeholders. Time Off's tab becomes real once this phase (balances) and 1.6 (requests) both land — re-add it then, not before. Tasks stays deferred to Phase 2.
- **`EmployeeTerminationJob` processes one tenant's due terminations per `TenantFacade.listActiveTenantIds()` pass**, called serially in a loop rather than in parallel. Fine at current dev/pilot tenant counts (PRD's stated scale: MHZ + 2-5 pilots); revisit if tenant count ever makes serial fan-out a real latency concern for the scheduled job — not before there's a benchmark showing a problem (CLAUDE.md §11).

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to Phase 1.6 (this file's 1.4 → 1.5 update is the template).
3. Commit `phase-1.5-leave-config` and open a PR against `main`.
4. Do not start Phase 1.6 in the same session.
