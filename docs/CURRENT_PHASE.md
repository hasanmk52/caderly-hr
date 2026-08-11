# Current Sub-Phase

**Working on:** Phase 1.4 — Employee CRUD + Profile
**Branch:** `phase-1.4-employee`
**Goal:** An Admin can add an Employee (invite → accept → active), edit their own record end to end (job, compensation, department/manager assignment, government IDs, bank details), and every Employee can view and edit their own profile within the fields they're allowed to touch. Introduces column encryption (government IDs, bank details) and history tables (status, manager) for the first time.

## Read these before doing anything

1. `docs/Helyx_Implementation_Plan.md` — the "1.4 Employee CRUD + Profile" section under Phase 1 — MVP
2. `docs/Helyx_PRD.md` — §14 (Employee Management — Detailed: lifecycle states, create/edit/terminate flows), §6.3 (FR-3.x — employee fields, government IDs, bank details, documents, field-level edit permissions), §6.4 (FR-4.2–4.4 — Department↔Employee relationship, exactly-one-department-at-a-time, manager assignment), §21 (`employee`, `employee_status_history`, `employee_manager_history`, `education`, `emergency_contact`, `government_id`, `bank_detail`, `benefit` schemas), §26 (permissions matrix), §10 for the acceptance-criteria format
3. `CLAUDE.md` — §5 (multi-tenancy contract — every new table is tenant-scoped), §6 A02 specifically (column encryption via `CryptoConverter`, AES-256-GCM, key from env var — new in this phase), §8 (testing rules), §10 (the "adding a new tenant-scoped entity" recipe)
4. `docs/UI_Guidelines.md` — §6 (tabs, forms), §7.1 (empty states), §8.5 (Profile page layout — left column + right tabs), §13/§14 (htmx/Alpine — now proven working, see ADR 0007)
5. `docs/adr/0004`, `0005`, `0006`, `0007` — tenancy mechanics, identity/session shape, and the htmx-CRUD transaction pattern (ADR 0007 — **read this one closely**: any endpoint here that needs a write followed by a fresh read (or several reads combined) must go through a dedicated `@Transactional` service-layer class, the way `OrganizationAdminService` does — never `@Transactional` on the controller itself (CLAUDE.md §7) — or it risks the same transient-empty-render bug 1.3 hit, root-caused and documented there)

## Already in place — do not redo

- **Everything from 1.1–1.3** (see git history): `TenantAwareEntity`, `TenantContext`, `TenantResolutionFilter`, `TenantIdentifierResolver`, `TenantSessionVariableListener`, the RLS template; `app_user` / `user_role` / `password_reset_token` and the full auth flow; `division` / `department` and the Organization admin page.
- **`org` package is feature-complete for what 1.4 needs to consume**: `DivisionService.listActive()` / `DepartmentService.listActive()` for populating the Employee form's department picker. Read `Division`/`Department` directly for now — no `OrgFacade` exists yet (deliberately deferred, see "Carried forward" below); 1.4 is exactly the phase that gives it a first real consumer, so add one now rather than reaching into `org`'s repositories directly from `people`.
- **`SecurityConfig`** — form login, BCrypt 12, `RoleHierarchy`, CSRF, headers, default-deny. **CSP now has an Alpine-CSP-safe path** (ADR 0007) — `x-data`/`x-model`/etc. work as-is via the `alpinejs__csp` webjar; do not add `'unsafe-eval'` and do not swap back to the plain `alpinejs` webjar.
- **htmx + Alpine are proven patterns now**, not first uses: `admin/organization.html` and `AdminOrganizationController` are the reference implementation for offcanvas-form-plus-table-swap. `OrganizationAdminService` (in `org`) is the reference implementation for ADR 0007's transaction shape — copy that pattern for any new endpoint that writes then reads back what it just wrote, or reads more than one thing in one request.
- **Error pages, exception hierarchy, RFC 7807, email outbox, UI shell, test scaffolding, ArchUnit rules, dev bootstrap, quality gates** — all as documented in prior phases' history. No changes needed.

## Remaining Phase 1.4 work

### Schema + entities (`people` package)

- Flyway migration for `employee`, `employee_status_history`, `employee_manager_history`, `education`, `emergency_contact`, `government_id`, `bank_detail`, `benefit` per PRD §21. All tenant-scoped: RLS template + `@TenantId`, `rls_probe` grants in the test migration.
- `employee.department_id` — now a **real FK** to `department`, and `department.head_employee_id` (nullable, no FK since Phase 1.3) gets its FK added in this migration too, per CURRENT_PHASE.md's own note from 1.3.
- Column encryption: `CryptoConverter` (AES-256-GCM) applied to `government_id.id_number` and every `bank_detail` field per CLAUDE.md §6 A02. Key from an env var — confirm the var name and local/dev provisioning before writing code that depends on it being present.
- `employee_status_history` / `employee_manager_history` populated by history listeners on status/manager change, not by hand in the service.

### Backend

- `EmployeeService`: create (INVITED status, reuses `InviteService`'s email-invite flow from 1.2), self-service edit (Employee can touch contact/address/emergency-contacts/documents; Admin can touch everything including job/compensation/employment-status), terminate (immediate if past/today, scheduled job if future — revokes sessions via the `SessionRegistry` call ADR 0006 already wired for password/role change).
- **Field-level permission enforcement**: a `PatchEmployeeDto` (or equivalent) that filters which fields a given role may set, not just endpoint-level `@PreAuthorize`. This is new territory — 1.2/1.3 only needed endpoint-level checks.
- **This is where the Department delete-or-archive guard from Phase 1.3 gets its "no active employees" half** (PRD FR-4.2): `DepartmentService.delete()` currently always hard-deletes (documented gap, see `DepartmentService`'s own Javadoc). Add the employee-count check here, following `DivisionService.deleteOrArchive()`'s shape.
- `/admin/employees` (Admin CRUD + list) and `/profile` (self-service) MVC controllers, `@PreAuthorize` on every method.

### Frontend

- People list page (list view only — grid/tree is Phase 2), filterable by department + status.
- Profile page per UI Guidelines §8.5: sticky left column (avatar, name, role badge, tenure — computed from `hire_date`, department/title, manager mini-card, peers), right column tabbed (Personal · Education · Job · Documents · Tasks · Time Off), htmx-loaded per tab, URL-reflected active tab.
- Admin-only compensation section, gated with `sec:authorize`.
- Reuse the offcanvas + htmx-swap-oob + toast pattern from `admin/organization.html` for anything that's a simple create/edit form; the Profile page's own inline-edit tabs are a new pattern (`hx-patch` per section) worth its own look before copying anything wholesale.

### Tests

- Integration + Playwright E2E for the full create → invite → accept → edit → terminate flow (this is the phase that finally justifies standing up Playwright — deferred twice already, see "Carried forward").
- Field-level permission tests: Employee cannot PATCH compensation; Manager can read a report's profile but not edit it.
- Tenant isolation test for every new entity (8 tables — this is the biggest isolation-test surface added in one phase so far).
- Encryption round-trip test: value written is unreadable at the raw column level, readable correctly through the entity.

## Definition of Done for Phase 1.4

- Admin adds an Employee, invitee accepts and sets a password, edits their own contact info, uploads a document (stub is fine if 1.7 — Files — hasn't landed; do not block on it), and sees their own profile side panel populated with an auto-calculated tenure.
- Termination revokes sessions immediately (past/today) or via the scheduled job (future).
- Employee cannot edit fields reserved to Admin (job, department, compensation, employment status) — enforced server-side, not just hidden in the UI.
- Department delete now correctly blocks (archives instead) when it has active employees — closing the Phase 1.3 gap.
- Every new entity has a tenant-isolation test; RLS + `@TenantId` on all 8 new tables.
- `./mvnw verify` green, PMD at zero violations, ArchUnit green with no new exemptions.

## Not in scope for Phase 1.4 — do not start any of this

- Employee custom fields — schema only (`employee_custom_field_definition`/`_value`), no UI (Phase 2, PRD §14.5)
- Grid/tree People views — Phase 2 (PRD §8.3)
- File storage / document upload UI — Phase 1.7, unless already landed by the time this phase starts
- Leave balance display on profile — Phase 1.5/1.6 own leave data
- `audit_entry` — Phase 1.11

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Playwright E2E is still not set up**, deferred again from 1.3 with the explicit note that 1.4 is "the first phase with enough screens to justify the harness." This is that phase — set it up here, don't defer a third time.
- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F — pick HaveIBeenPwned checker or a real breach corpus when it matters.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **`OrgFacade` does not exist yet.** Phase 1.3 deferred it since it had no cross-module consumer. This phase (`people` reading `org`) is that consumer — decide whether to add the facade seam now or read `Division`/`Department` directly a little longer, and write down which (mirrors the decision `DivisionService`/`DepartmentService` themselves had to make in 1.3).
- **Phase 1.3's transient-empty-render bug is root-caused** (not just worked around) — ADR 0007 has the log-backed diagnosis (`TenantSessionVariableListener` never re-firing for a second, unrelated transaction in the same request) and the fix (`OrganizationAdminService` combines write+read into one transaction). *Why* the listener doesn't re-fire wasn't chased further — that's the starting point if a similar symptom appears somewhere this pattern can't be applied directly.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to Phase 1.5 (this file's 1.3 → 1.4 update is the template).
3. Commit `phase-1.4-employee` and open a PR against `main`.
4. Do not start Phase 1.5 in the same session.
