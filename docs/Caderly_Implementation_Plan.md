# Caderly — Implementation Plan

**Companion to:** `Caderly_PRD.md` v1.0
**Version:** 1.0
**Date:** 2026-07-20

Solo developer capacity assumption: ~4 focused dev-days/week (accounting for the day job and life). All estimates in dev-weeks at that pace. Complexity: **S**=1-2 weeks, **M**=3-5 weeks, **L**=6-10 weeks, **XL**=10+ weeks.

Total MVP estimate: **~14.5-18.5 weeks** (~3.5-4.5 months).

---

## Phase 0 — Foundations (1 week, S)

### Objectives
Bootstrap the project skeleton, CI, and local dev experience so the first feature commit is a small change to a working system.

### Features / Deliverables
- Empty Spring Boot 4.1 app that runs on `http://localhost:8080/` with a "Hello from Caderly" page.
- Connects to a **host-installed PostgreSQL 17** using `application-dev.yml`. Developer creates the database manually (`createdb caderly`). No Postgres in Docker for local dev.
- Testcontainers (used by the test suite) still runs an ephemeral Postgres in Docker — this is unrelated to dev DB.
- Flyway wired, one baseline migration creating an empty schema.
- Health check at `/actuator/health`.
- CI on GitHub Actions: lint, test, build Docker image, push to GHCR.
- Base Bootstrap 5 + Thymeleaf layout template.

### Database changes
- Baseline empty Flyway migration.
- Developer setup step in README: `createdb caderly`, `createuser caderly --pwprompt`, `GRANT ALL ON DATABASE caderly TO caderly`.

### Backend tasks
- `spring init` with modules: web, security, data-jpa, thymeleaf, actuator, validation, mail.
- Add: htmx (WebJar), Alpine.js (WebJar), Bootstrap 5 (WebJar).
- Add: Testcontainers, Playwright-Java, ArchUnit, JaCoCo.
- Configure Flyway, Hibernate settings, JSON logging.
- Configure Maven `pom.xml` with Java 25 (already scaffolded).
- Set up `application.yml` with profiles (`dev`, `test`, `prod`) and 12-factor env-var overrides.

### Frontend tasks
- Base layout `layout.html` with Thymeleaf fragments: `head`, `topbar`, `sidebar`, `footer`.
- Bootstrap 5 imported, htmx script tag, Alpine script tag, custom CSS file `caderly.css`.
- Placeholder home page.

### Testing requirements
- One controller test proving the "hello" page returns 200.
- One Testcontainers integration test proving Postgres connects.

### Definition of Done
- With host Postgres running and `caderly` DB created, `./mvnw spring-boot:run` starts the app on port 8080 and shows "Hello".
- `./mvnw test` passes (Testcontainers spins up its own ephemeral Postgres via Docker).
- Pushing to `main` triggers CI, image lands in GHCR.
- README documents setup in <10 steps (including Postgres user + DB creation).

### Dependencies
- None (starting point).

---

## Phase 1 — MVP (12-14 weeks, L)

**Objective:** Replace TalentHR free plan for MHZ's daily use, with full multi-tenant isolation and auditability.

### 1.1 Multi-tenancy foundation (1 week)

**Features:** Tenant subdomain resolution, `TenantContext`, Hibernate filter, base entity plumbing.

**DB changes:**
- Create `tenant`, `super_admin` tables.
- Create `common.BaseEntity` mapped-superclass with `id, created_at, updated_at`.
- Create `common.TenantAwareEntity extends BaseEntity` with `tenant_id`.
- Add Postgres RLS policies template.

**Backend tasks:**
- `TenantResolutionFilter` (Spring `Filter`) resolves from `Host` header, caches tenant lookup in Caffeine.
- `TenantContext` ThreadLocal with `try { set } finally { clear }` pattern.
- `@TenantId` (Hibernate 7 discriminator multi-tenancy) on `TenantAwareEntity`'s `tenant_id` field — Hibernate arms the restriction itself on every query it generates for that entity (including `find(id)`) and auto-populates the column on insert; no hand-written `@FilterDef`/`@Filter` and no `@PrePersist` listener.
- `TenantIdentifierResolver` (a `CurrentTenantIdentifierResolver` bean) tells Hibernate the current tenant, backed by `TenantContext`.
- `TenantSessionVariableListener` (a Spring `TransactionExecutionListener`, auto-registered by Spring Boot — not an AOP aspect) runs `SET LOCAL app.tenant_id = ?` at the start of every transaction, for the RLS backstop.
- `ArchUnit` test: any `@Entity` in a tenant-scoped package must extend `TenantAwareEntity`. **Exempt:** entities in a `.system` sub-package of any module (system infrastructure — `EmailOutbox`, `AuditEntry`, `LoginAudit`, `Tenant`, `SuperAdmin`). Rule enforced by package pattern, not by manual allowlist per class.
- See ADR 0004 for why this landed on native Hibernate multi-tenancy instead of the hand-rolled `@Filter` + AOP aspect originally planned here, and the `spring.data.jpa.repositories.bootstrap-mode: lazy` setting it required.

**Frontend tasks:**
- Home page shows `{tenant.name}` from `TenantContext` proving resolution works.

**Testing:**
- Integration test: seed 2 tenants + 2 employees; request as tenant A returns only A's data. RLS proven independently via a raw JDBC connection under a non-superuser role (Hibernate's restriction can't be "switched off" the way the originally-planned `@Filter` could, so the isolation test bypasses the ORM entirely for this proof rather than disabling a filter).

**DoD:**
- Any new entity requires zero code to be tenant-safe if it extends `TenantAwareEntity`.
- ArchUnit test fails PR if a non-tenant entity is added to a tenant package.

**Complexity:** M. **Depends on:** Phase 0.

---

### 1.2 Authentication & Users (2.5 weeks)

**Features:** Email/password login, invite flow, password reset, session management, RBAC. Includes the **minimal email outbox infrastructure** so invite + reset emails are durable from day one (richer templates + full event catalog land in 1.10).

**DB changes:**
- `app_user`, `user_role`, `password_reset_token` — tenant-scoped, extend `TenantAwareEntity`, `@TenantId` + RLS as per §5. (No `token_revocation` table: that was a JWT-era artifact. PRD §19.1 now specifies server-side sessions, where revocation means deleting the user's sessions from the session store.)
- `email_outbox` — **system-scoped infrastructure table**, same category as `audit_entry` / `login_audit`. Does NOT extend `TenantAwareEntity`, does NOT have `@TenantId`, does NOT have RLS. `tenant_id` is a *nullable reference column* used by the dispatcher to look up branding and by the Admin viewer to filter — not the tenancy discriminator. Columns: `id, tenant_id (nullable ref to tenant), to_email, subject, body_html, status (PENDING|SENT|FAILED), attempts, last_error, created_at, next_attempt_at, sent_at`. Lives in a `.system` sub-package so ArchUnit's "every tenant-package entity extends TenantAwareEntity" rule excludes it by construction.

**Backend tasks:**
- `SecurityConfig`: form login (Thymeleaf), **server-side session cookie** (Spring Security default, `HttpOnly` + `Secure` + `SameSite=Lax`), CSRF enabled, and user lookup scoped to the current tenant. Session invalidated on password change, role change, or termination via `SessionRegistry`.
  - *Built without a custom `AuthenticationProvider`, which this plan originally specified.* `AppUser` extends `TenantAwareEntity`, so Hibernate's `@TenantId` restriction already scopes `findByEmail` — stock `DaoAuthenticationProvider` over a plain `UserDetailsService` has the property the custom provider was there to add, without hand-writing a `tenant_id` predicate that CLAUDE.md §11 lists as an anti-pattern. See ADR 0006 decision A.
- Error pages: `templates/error/` covers 403, 404, 4xx and 5xx, and `spring.web.error.*` is pinned so no response carries a stack trace. Not in the original task list; added in review after a 403 was found rendering Boot's Whitelabel page with a full trace.
- BCrypt encoder, cost 12.
- `EmailOutboxService.enqueue(tenantId, to, subject, bodyHtml)` — writes to `email_outbox` in the same transaction as the calling business action; no direct SMTP call. Callers pass `TenantContext.currentTenantId()` explicitly (or `null` for Super Admin system emails).
- `EmailDispatcher` — `@Scheduled(fixedDelay = 30s)` runs **system-scoped** (no `TenantContext`, no per-tenant filter) and polls all `PENDING` rows across all tenants in one query. Sends via `JavaMailSender`, marks `SENT`; on failure increments `attempts`, sets `next_attempt_at` with exponential backoff (30s → 2m → 10m), and after 3 failed attempts marks `FAILED` with the last error. Log at `warn` on transient failure, `error` on `FAILED`. The dispatcher never uses tenant-scoped repositories — it queries `email_outbox` directly.
- Inline plain HTML for the two Phase-1.2 emails (invite + password reset). Tenant-branded Thymeleaf templates come in 1.10.
- `InviteService`: create user with INVITED status, generate 32-byte token stored SHA-256-hashed, call `EmailOutboxService.enqueue(...)`.
- `PasswordResetService`: same pattern — token generated, hashed, enqueued.
- Login rate limiter (Bucket4j).
- Failed login counter + lockout.
- Role hierarchy: ADMIN > MANAGER > EMPLOYEE (Spring `RoleHierarchy`).
- `@PreAuthorize` on all controllers.
- Session revocation on password change.

**Retroactive note (2026-08-22, ADR 0011):** `user_role` grants here were originally invite-time-only (`Set.of(Role.EMPLOYEE)` for every new employee, with no later path to grant `MANAGER`). PRD §5's "MANAGER is derived from the org chart" was never actually wired up — found during Phase 1.6 manual testing, when a real manager (set via `employee.manager_id`) got a 403 on `/for-action`. Closed by an event published from `EmployeeService.reassignManagerInternal` (Phase 1.4) plus a one-time backfill migration for org charts that predate the fix; see ADR 0011. Not a new phase — a fix to this phase's and 1.4's existing scope.

**Frontend tasks:**
- Login page (matches TalentHR aesthetic — top-center card, logo, email/password, "Forgot password?" link, "Log in" button).
- Set-password (invite acceptance) page.
- Forgot-password + reset-password pages.
- User avatar menu (top-right) with Logout.

**Testing:**
- Auth integration tests, lockout after 5 fails, invite flow end-to-end.
- **Outbox durability test:** enqueue email in a transaction, force JVM restart before dispatcher runs, assert row still present as `PENDING` on restart, dispatcher picks it up within one poll cycle.
- **Outbox retry test:** stub `JavaMailSender` to throw on first 2 attempts, assert 3rd attempt succeeds, `attempts=3`, `status=SENT`.
- MailHog Testcontainer verifies full outbox → SMTP → inbox path.
- Playwright E2E: create user (via API), send invite, accept, log in, see home.
- Security test: request `/admin/*` as Employee → 403.

**DoD:**
- Login works with tenant subdomain. Wrong tenant → user not found.
- Invite email arrives in MailHog inbox in dev after a `./mvnw spring-boot:run` invite.
- **Killing the app mid-invite (`SIGKILL` after enqueue commit, before dispatch) does not lose the invite** — restart, dispatcher picks it up and delivers within one poll cycle.
- CSRF blocks form POST without token.

**Complexity:** M. **Depends on:** 1.1.

---

### 1.3 Organization — Divisions & Departments (0.5 week)

**Features:** Admin CRUD on Divisions + Departments.

**DB changes:** `division`, `department` tables.

**Backend:** Repository, Service, DTOs, `/admin/divisions`, `/admin/departments` MVC + REST endpoints.

**Frontend:** Admin console pages with Bootstrap tables + slide-over form (Alpine.js offcanvas), htmx-powered row updates.

**Testing:** CRUD integration tests; RBAC test (Employee gets 403 on these routes).

**DoD:** Admin can add Division → add Department → assign to Division. Cannot delete Department with active employees.

**Complexity:** S. **Depends on:** 1.2.

---

### 1.4 Employee CRUD + Profile (2 weeks)

**Features:** Full employee lifecycle from Admin create + invite, self-service edit, view profile.

**DB changes:**
- `employee`, `employee_status_history`, `employee_manager_history`, `education`, `emergency_contact`, `government_id`, `bank_detail`, `benefit`.

**Backend:**
- `EmployeeService` with create/update/terminate flows.
- History listeners populate status/manager history on change.

**Retroactive note (2026-08-22, ADR 0011):** manager reassignment (`EmployeeService.reassignManagerInternal`) now also publishes `ManagerRoleSyncEvent`, so the affected employees' `MANAGER` role in `user_role` (Phase 1.2) stays in sync with the org chart, per PRD §5. Found missing during Phase 1.6 manual testing; see ADR 0011.
- Column encryption via a `CryptoConverter` for `government_id.id_number`, `bank_detail.*`, optional `employee.base_compensation`.
- Field-level permission: `PatchEmployeeDto` filters mutable fields based on requester role.
- Invite email on employee creation (reuses 1.2).

**Frontend:**
- People list page (list view, MVP; grid/tree in Phase 2). Filter by dept + status.
- Profile page with left column (avatar, name, badge, tenure, dept/title/location, manager, peers, contact) + right tabs (Personal, Education, Job, Documents, Tasks, Time Off). All rendered via Thymeleaf fragments; htmx swaps tab contents.
- Personal tab form with inline save (htmx `hx-patch`).
- Emergency Contacts + Government IDs sub-forms.
- Admin-only compensation section (rendered conditionally via Spring Security tags).

**Testing:**
- Integration + Playwright E2E for the create-invite-accept-edit-terminate flow.
- Assert Employee cannot PATCH compensation.
- Assert Manager can read report but not edit.

**DoD:**
- Admin adds employee, invitee sets password, edits contact info, uploads a document (from 1.7), sees own profile side panel populated (tenure auto-calculated from hire_date).
- Termination revokes sessions immediately.

**Complexity:** L. **Depends on:** 1.2, 1.3.

---

### 1.5 Leave Types, Holidays, Balances (1 week)

**Features:** Configuration of leave policy; nightly grant job; on-hire pro-rate.

**DB changes:** `leave_type`, `public_holiday`, `leave_balance`.

**Backend:**
- `LeaveTypeService` CRUD.
- `PublicHolidayService` CRUD + CSV bulk upload.
- `BalanceService.grantAnnual(year)` idempotent, runs Jan 1 via `@Scheduled(cron)`.
- `BalanceService.grantOnHire(employee)` pro-rates by months remaining.
- Manual adjustment endpoint (Admin, with required reason → audit).

**Frontend:**
- Admin: Leave Types CRUD grid.
- Admin: Holidays page with calendar picker + CSV upload.
- Employee: Home dashboard "Book Time Off" cards show balance per type (uses this data).

**Testing:**
- Grant is idempotent (running twice doesn't double).
- Pro-rate math correct at various hire dates.

**DoD:** Admin defines 3 leave types + uploads UAE 2026 holidays; employee sees balances on home; running `grant(2027)` in Jan produces fresh balances.

**Complexity:** M. **Depends on:** 1.4.

---

### 1.6 Leave Requests + Approvals (2 weeks)

**Features:** Book, approve/reject, cancel, notify.

**DB changes:** `leave_request`.

**Backend:**
- `LeaveDurationCalculator` — the algorithm from PRD §12.3, unit-tested exhaustively.
- `LeaveRequestService.book()` — balance check, calculate duration, create PENDING, enqueue approver email, audit.
- `LeaveRequestService.approve/reject/cancel()` with state machine + optimistic locking on `leave_balance`.
- Approver resolution: employee.manager_id → fallback Admin.
- Email templates (invite, reset, requested, approved, rejected, cancelled).

**Frontend:**
- Book Time Off modal (accessible from top bar CTA and Home dashboard cards). Type selector, date picker, half-day toggles, live duration, note, submit.
- Profile → Time Off tab: budget cards + history table.
- For Action page: pending requests for me to approve (Manager/Admin scope).
- Approve/reject modal with note field.

**Testing:**
- Exhaustive unit tests on duration calculator (DST, year boundary, tenant weekend variants, half-day edge cases from AC-LV.4).
- Integration test: book → approve → balance updated → email queued.
- E2E: Priya books 3 days, Rahul approves, Priya sees APPROVED in history.

**DoD:**
- Full happy path works. Insufficient balance blocked with clear error. Cancel returns balance.

**Complexity:** L. **Depends on:** 1.5.

---

### 1.7 Files & Documents (1 week)

**Features:** Company files + employee documents with pluggable storage.

**DB changes:** `company_file`, `employee_document`.

**Backend:**
- `FileStorage` interface + `LocalFileStorage` + `S3FileStorage`.
- Multipart upload endpoint with MIME + magic-byte validation (Apache Tika) + size limit + extension whitelist.
- Presigned URL generation for S3 downloads; streaming download for local.

**Frontend:**
- Files (company) page: table + upload button (Admin).
- Profile Documents tab: upload button (self + Admin), download.

**Testing:**
- Upload blocked for `.exe`, `.js` files.
- Upload > limit returns 413.
- Local + S3 backends both pass same test suite (test profile toggles impl).

**DoD:** Admin uploads handbook to Files; Employee downloads it. Priya uploads passport scan to her Documents.

**Complexity:** M. **Depends on:** 1.2.

---

### 1.8 Team Calendar (1 week)

**Features:** Grid calendar view, iCal feed.

**DB changes:** `user_ical_token` (or store token as column on `app_user`).

**Backend:**
- `CalendarService.buildTeamCalendar(from, to, filter)` returning employee-day matrix.
- iCal feed endpoint (`/api/v1/calendar/ical.ics?token=`). Public (no session), authenticated by token. Returns approved leave as VEVENTs.

**Frontend:**
- Calendar page: month grid, rows = employees, cells with colored bars per leave type.
- Filter panel (dept, division, type).
- Settings → Calendar integration page shows unique iCal URL with copy button.

**Testing:**
- iCal validator passes.
- Grid renders correctly across month boundaries.

**DoD:** Priya subscribes iCal in Google Cal → sees her approved leave next-day.

**Complexity:** M. **Depends on:** 1.6.

---

### 1.9 Home Dashboard + For Action inbox (0.5 week)

**Features:** Widgets and pending-action list.

**DB changes:** none new.

**Backend:** Aggregate query endpoints for each widget.

**Frontend:** Home page composed of widget fragments (htmx `hx-get` each on load — parallel).

**Testing:** Widget renders empty state gracefully.

**DoD:** Home matches TalentHR home in structure and function.

**Complexity:** S. **Depends on:** 1.6, 1.7, 1.8.

---

### 1.10 Notifications — templates, event wiring, admin view (0.5 week)

**Features:** Full transactional-email catalog on top of the outbox infrastructure already shipped in 1.2.

**DB changes:**
- Extend `email_outbox` if new columns are needed (e.g., `event_type` for filtering, `template_key`). Additive migration only.

**Backend:**
- Thymeleaf email templates in `src/main/resources/templates/email/*.html`, one per event.
- `EmailTemplateService.render(templateKey, model)` — renders template with per-tenant branding merged in (logo URL, primary color, tenant name).
- Wire every notification event listed in PRD §17.2 (leave requested/approved/rejected/cancelled, holiday reminder, birthday, work anniversary, document expiring — password reset + invite already wired in 1.2) to call `EmailOutboxService.enqueue(...)`.
- Admin outbox viewer: `/admin/notifications` page with paginated table (date, to, subject, status, attempts, last error), filter by status/date, "retry" action for FAILED rows.
- Per-tenant notification toggles (disable birthday/anniversary/etc.) — one settings page.

**Testing:**
- Snapshot tests for each rendered template (branding merge correct, no missing tokens).
- Integration test per event: perform business action, assert correct `email_outbox` row created with correct recipient(s).
- E2E: Admin sees a FAILED row after simulated SMTP outage; clicks retry; row transitions to SENT after MailHog is back.

**DoD:** Every event in PRD §17.2 produces a correctly-branded email in MailHog. Admin can inspect failed sends and retry.

**Complexity:** S. **Depends on:** 1.2 (outbox infra), 1.6 (leave events).

---

### 1.11 Audit Log (0.5 week)

**Features:** Write + login audit + Admin viewer.

**DB changes:** `audit_entry`, `login_audit`.

**Backend:**
- `@EntityListeners(AuditListener.class)` on every entity — captures `@PostPersist`, `@PostUpdate`, `@PreRemove` with before/after JSON via Jackson.
- Spring Security `AuthenticationEventPublisher` writes to `login_audit` on success/failure.

**Frontend:**
- Admin → Audit Log page: filter form + table + "view diff" modal (JSON pretty-print with react-json-view alternative? Or plain `<pre>`).
- Admin → Login Audit page.

**Testing:** Every service method covered writes to audit. Test asserts audit for a compensation update.

**DoD:** Admin can trace who changed what and when.

**Complexity:** S. **Depends on:** 1.4.

---

### 1.12 Reports MVP (0.5 week)

**Features:** Balance, Utilization, Headcount reports with CSV export.

**DB changes:** none (aggregations on existing).

**Backend:**
- `ReportService` methods returning DTOs.
- CSV export via `com.opencsv`.

**Frontend:**
- Admin → Reports page with 3 report cards.
- Each opens a form (filter, date range) + preview table + Download CSV button.

**Testing:** Report totals correct with seeded data.

**DoD:** Admin generates each report, downloads CSV, opens in Excel.

**Complexity:** S. **Depends on:** 1.6.

---

### 1.13 Super Admin console (0.5 week)

**Features:** Tenant provisioning, suspend, delete, impersonate.

**DB changes:** none new.

**Backend:**
- Separate `SecurityFilterChain` for `/superadmin/**` with distinct authentication realm (`super_admin` table).
- IP allowlist filter (env var).
- Impersonation endpoint sets a spoofed `TenantContext` + `Authentication` for one session; every audit entry marks `actor_role=SUPERADMIN_IMPERSONATING`.

**Frontend:**
- Tenant list + create form + suspend/delete actions.

**Testing:** Impersonation audit trail correct; IP allowlist enforced.

**DoD:** Hasan can spin up a new tenant + first Admin in one form submit.

**Complexity:** S. **Depends on:** 1.1, 1.2.

---

### 1.14 Deployment & Ops (1 week)

**Features:** Debian install guide, Docker Compose file (app + reverse proxy only — Postgres is native on the host), backup, TLS.

**Tasks:**
- Multi-stage Dockerfile with distroless JRE 25.
- `docker-compose.yml` with `caderly-app` + `caddy` only. Postgres is installed natively on the Debian host via `apt install postgresql-17`. The app connects to `host.docker.internal:5432` (or the host's LAN IP) — documented in `INSTALL.md`.
- Caddyfile with automatic TLS for `*.caderly.app`.
- Nightly `pg_dump` cron on the host, upload to off-site S3.
- `.env.example` documented (DB URL, DB user, DB password, SMTP, encryption key, etc. — no DB service inside compose).
- `INSTALL.md` for MHZ Debian VPS: install Java tooling only if needed for admin scripts, install Postgres 17 via apt, create `caderly` role + `caderly` database, `pg_hba.conf` config for local + Docker bridge access, then `docker compose up -d`.
- Prometheus scrape endpoint.
- Wildcard DNS setup guide.
- Optional Kubernetes / cloud variant documented separately: same image, external managed Postgres (RDS / Cloud SQL / Neon), same env vars — no code change.

**Testing:**
- `docker compose up` on a fresh Debian box (after Postgres is installed + DB created) works end-to-end.
- Restore-from-backup drill documented and run once.

**DoD:** MHZ instance running on a $10/mo Hetzner VPS with Postgres 17 native, Docker running `caderly-app` + `caddy`, wildcard TLS working for `mhz.caderly.app` (or the chosen domain).

**Complexity:** M. **Depends on:** everything above.

---

### Phase 1 Definition of Done (rollup)

- All PRD §6 (Functional Requirements) MVP items shipped.
- Test suite: >70% line coverage; every acceptance criterion in §10 has an integration or E2E test.
- MHZ can migrate off TalentHR: user CSV import script exists, leave balances entered.
- Backup restore drill successful.
- README + INSTALL guide complete.
- Security review checklist run (OWASP Top 10 + tenant-isolation audit).

---

## Phase 1.5 — MFA + Data Import (2 weeks, M)

Bridge phase before Phase 2 to lock down security and enable real migration.

### Objectives
- Ship MFA for Admin accounts before real MHZ data lands.
- Ship a CSV import for employees + historical leave balances.

### Features
- TOTP MFA (Google Authenticator compatible), enforceable per tenant per role.
- Employee CSV import wizard (validate → preview → confirm → import → error report).
- Leave balance CSV import.

### DB changes
- `app_user.mfa_secret`, `mfa_enabled`, `mfa_backup_codes`.

### Backend
- `dev.samstevens.totp` library integration.
- Import service with dry-run mode.

### Frontend
- MFA setup wizard on user Settings.
- Import wizard under Admin.

### Testing
- MFA end-to-end.
- Import rollback on error.

### DoD
- MHZ Admins have MFA enabled.
- 10 employees imported from TalentHR export in one shot.

### Complexity: M. Depends on Phase 1.

---

## Phase 2 — Depth & Polish (~10-14 weeks, L)

### Objectives
Add the high-value features that TalentHR free lists but MVP skipped, plus quality-of-life improvements.

### Features
1. Onboarding/offboarding checklist templates (assign on hire/leave, track completion).
2. Slack + MS Teams webhook notifications (per tenant).
3. Google + Microsoft SSO.
4. Custom Report Builder (entity + columns + filters + group-by + save).
5. Document expiry reminders — richer UI (already scheduled in MVP).
6. Arabic + RTL support.
7. Public REST API + tenant API keys (with scope-limited permissions).
8. Team calendar iCal feed.
9. Grid + Org-tree views on People page.
10. Company News + Announcements (Admin posts on home widget).
11. Employee custom fields (Admin defines, exposed in profile).
12. Per-user notification preferences.
13. Delegation ("I'm out until X, approve to Y").
14. Audit log CSV export.

### DB changes
- `checklist_template`, `checklist_task`, `checklist_instance`, `checklist_task_instance`.
- `custom_field_definition`, `custom_field_value`.
- `api_key` (per tenant).
- `announcement`.
- `user_notification_preference`.
- `delegation`.
- `webhook_endpoint`, `webhook_delivery`.

### Backend tasks
- OAuth2 client for Google + Microsoft (Spring Security OAuth2 Client).
- Webhook dispatcher with signature (HMAC-SHA256) + retry.
- Report builder domain-specific-query builder.
- i18n message bundles for AR; RTL CSS variant.

### Frontend tasks
- Checklist admin (template editor: tasks with type = form field / file upload / manual done).
- Checklist runtime UI on employee profile ("Your onboarding: 3/7 done").
- SSO buttons on login page.
- Report builder UI (drag-drop columns, filter builder).
- Language toggle in Settings; direction detection.

### Testing
- Every module gets integration + one E2E.
- SSO end-to-end against Google/Microsoft dev tenants.

### DoD
- Onboarding checklist assigned to new hire auto-generates tasks on hire date.
- MHZ tenant runs with all Admins on SSO.
- Sample Arabic user reads full app RTL correctly.

### Complexity: L. Depends on Phase 1.

---

## Phase 3 — Performance Reviews (~10-12 weeks, L)

### Objectives
Add the module you flagged as the top post-MVP investment.

### Features
1. Review cycles (quarterly/annual), manager-defined.
2. Goals / OKRs (owner, key results, progress, alignment tree).
3. 1:1 meeting notes (private between manager + report; agenda templates).
4. 360 feedback (peer + manager + self, anonymized or named per config).
5. Performance summary export.
6. Attendance tracking (basic clock in/out with browser + geolocation).
7. Payroll CSV export presets per country.

### DB changes
- `review_cycle`, `review_assignment`, `goal`, `key_result`, `one_on_one_note`, `feedback_request`, `feedback_response`.
- `attendance_punch`.

### Backend
- Review workflow engine (state machine).
- Goal alignment tree traversal.

### Frontend
- Review cycle dashboard.
- Goals page with kanban / tree.
- 1:1 note-taking with rich text.
- 360 feedback form generator.

### Testing
- Complex state-machine coverage.

### DoD
- MHZ runs its first quarterly review cycle in Caderly.

### Complexity: L. Depends on Phase 2.

---

## Phase 4 — Adjacent Modules (~ongoing)

- Recruitment / ATS (jobs, candidates, pipeline, interviews, offers).
- Learning Management (courses, assignments, tracking).
- Payroll integration with named providers.
- Public marketing site + self-serve signup + Stripe billing.
- Native mobile app (React Native or Flutter, consuming existing REST API).
- Custom domains per tenant.
- SAML SSO.
- Enterprise SSO provisioning (SCIM).

Prioritized based on customer demand — no fixed schedule.

---

## Cross-Phase Commitments

### Testing strategy (applies to every phase)
- **Unit** (JUnit 5 + Mockito + AssertJ): pure logic, 70%+ coverage.
- **Integration** (Spring `@SpringBootTest` + Testcontainers Postgres): every service happy + one sad path; every controller RBAC.
- **Architecture** (ArchUnit): tenant isolation, module boundaries.
- **Contract** (RestAssured against running app): every public endpoint.
- **E2E** (Playwright-Java): one happy-path per module.
- **Performance** (Gatling, optional): baseline for critical endpoints in CI nightly.
- **Security** (OWASP ZAP baseline scan): weekly CI job.

### CI/CD strategy (applies to every phase)
- GitHub Actions: on PR → lint + test + build. On merge to `main` → build Docker image tagged `main-<sha>` → push to GHCR.
- Manual promote workflow: tag a release → CI builds `v1.2.3` image → deploy step SSHes to Debian and runs `docker compose pull && docker compose up -d`.
- Cloud tenants: same image, terraform + `kubectl set image` or similar.

### Database migration strategy
- Flyway forward-only.
- Every migration reviewed for backwards compat: additive first (add column nullable + backfill + make NOT NULL) so zero-downtime deploy is possible from day 1.
- No `DROP COLUMN` without a 2-release deprecation window.

### Backup strategy
- Nightly `pg_dump` → local + off-site S3 (versioned).
- Weekly restore-to-scratch verification.
- Monthly full DR drill (spin up from scratch on a fresh VPS, restore backup, run smoke tests).

### Documentation strategy
- Living: `README.md` for setup, `INSTALL.md` for deploy, `docs/adr/` for Architecture Decision Records (one per significant choice, e.g. "ADR-001: Shared-schema multi-tenancy").
- API: OpenAPI auto-generated + published to `/api/docs`.
- User guide (`docs/user/`): per role, one-page each, versioned.
- Runbook (`docs/ops/`): common ops tasks (add tenant, restore backup, rotate key).

---

## Sequencing rationale

Why in this order?
- **Phase 0** first because a working CI pipeline is a force-multiplier.
- **1.1 (tenancy)** before anything user-visible because retrofitting tenancy is expensive and dangerous.
- **1.2 (auth)** before any authenticated feature.
- **1.3 → 1.4** in order because employees need departments.
- **1.5 → 1.6** because leave depends on types + balances existing.
- **1.10, 1.11** intentionally cheap and near end — they wrap existing features rather than create new ones.
- **1.14 (deploy)** last because you don't want to iterate on infra with no product to test it.
- **Phase 1.5** inserted before Phase 2 to lock security and enable real MHZ migration.
- **Phase 2** adds depth once the base is stable and you have real MHZ usage feedback.
- **Phase 3** is your explicitly-flagged priority module.
- **Phase 4** is demand-driven.

---

## Risks specific to the plan

- **Solo-dev burn rate.** 14+ weeks of focused MVP is real work on top of a day job. If MHZ pushes for a shorter timeline, cut Phase 1.12 (reports) and 1.13 (super admin console — do provisioning via SQL scripts) — saves ~1.5 weeks with acceptable pain.
- **Getting stuck on tenant isolation edge cases.** Budget +1 week buffer in Phase 1.1; if it stretches, you've saved yourself weeks of debugging later.
- **First-time Spring Boot 4 quirks.** SB 4 is 6 weeks old at project start; there may be documentation gaps. Fallback to SB 3.3 LTS if a blocker appears — API-compatible, minimal code change.
- **Getting the leave duration algorithm wrong.** This is a deceptively tricky function (DST, year boundary, weekend variants, half-day combos). Time-box implementation to 3 days, write tests first, don't skip.

---

*End of Implementation Plan — v1.0*
