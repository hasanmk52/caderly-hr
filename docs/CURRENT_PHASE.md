# Current Sub-Phase

**Working on:** Phase 1.11 — Audit Log (write audit + login audit + Admin viewer)
**Branch:** `phase-1.11-audit-log` (not yet created — create it before writing any code)
**Goal:** Every write operation and every login attempt is recorded durably with before/after
state, and Admin can trace who changed what and when from a filterable viewer.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.11 Audit Log" section under Phase 1 — MVP
2. `docs/Caderly_PRD.md` — §6.11 (Audit Log FR-11.x), §18 (Audit Logs — Detailed: `audit_entry` and
   `login_audit` schemas §18.1, implementation approach §18.2, Admin UI §18.3, retention §18.4 —
   retention/archival is explicitly Phase 2, don't build it early)
3. `CLAUDE.md` — §6 A09 (Security Logging and Monitoring Failures: every write through
   `AuditListener`, every login attempt to `login_audit`, structured JSON logs with request
   ID/tenant ID/actor ID in MDC, no secrets/tokens/PII in logs — there is already a CI grep test
   guarding this, check what it covers before assuming a gap), §7 (package structure names an
   `audit` module: `AuditEntry`, `LoginAudit`, `AuditListener`)

## Already in place — do not redo

- **Structured JSON logs to stdout** already exist per CLAUDE.md §6 A09 — confirm MDC already
  carries request ID/tenant ID/actor ID before assuming this phase adds it; it may already be
  wired from an earlier phase's logging setup.
- **`TenantContext`/`TenantSessionVariableListener`** (Phase 1.1): every write already runs inside a
  resolved tenant context, so `AuditListener` can read `tenant_id` and the acting user from there
  rather than threading them through every call site.
- **`identity.AppUserDetailsService`/Spring Security's authentication pipeline** (Phase 1.1): login
  success/failure already flows through Spring Security's `AuthenticationEventPublisher` machinery
  — this phase wires a listener onto it, not the authentication flow itself.
- **`notifications` module's outbox pattern** (Phase 1.2, extended 1.10): if audit writes ever need
  to trigger anything external (they shouldn't for MVP — audit is app-internal), the durable-outbox
  contract in CLAUDE.md §6a still applies. Expect audit writes to be synchronous, in-transaction
  JPA persists, not outbox rows.

## Remaining Phase 1.11 work

### Backend

- **DB:** `audit_entry` and `login_audit` tables per PRD §18.1's exact column lists. Confirm both
  tables' tenancy shape before writing the migration: `login_audit.user_id` is nullable ("if
  unknown" — an unrecognized email attempt has no `AppUser` to reference), and check whether either
  table follows the `email_outbox`/`audit`-as-infrastructure pattern (ADR 0005 decision B: no
  `@TenantId`, no RLS, `tenant_id` as a plain filterable reference) or is genuinely tenant-scoped
  with RLS like ordinary business data — PRD §18.1 lists `tenant_id` on both, but that alone doesn't
  settle whether Super Admin's cross-tenant view (PRD §26: "View audit log ... ✅ cross-tenant" for
  Super Admin) is easier to build one way or the other. Decide and record the reasoning as an ADR
  the way ADR 0005 did for `email_outbox`.
- `audit.AuditEntry`, `audit.LoginAudit` entities + repositories.
- `audit.AuditListener` — Hibernate `@EntityListeners` (`@PostPersist`, `@PostUpdate`, `@PreRemove`)
  capturing before/after JSON via Jackson, wired onto every entity that needs it. Decide whether
  "every entity" truly means every `@Entity` in the codebase or a named subset — PRD §11.1 says
  "every write operation," but `email_outbox`'s own status transitions (PENDING→SENT/FAILED) firing
  audit rows on every dispatcher poll is probably noise, not signal. Write this decision down.
- Spring Security `AuthenticationEventPublisher` → writes `login_audit` on success/failure.
- Admin viewer service: filter by date range/actor/entity type/action, paginated (the app's second
  paginated list — `notifications.NotificationAdminService`/`/admin/notifications`, sub-phase 1.10,
  is the first; reuse its `Page`/`Pageable` + prev/next-only pattern rather than inventing a new one
  — see `docs/UI_Guidelines.md` §6 Pagination).

### Frontend

- `templates/admin/audit-log.html` — filter form + table + "view diff" modal (JSON pretty-print;
  PRD §18.3 floats `react-json-view` or a plain `<pre>` — no React in this stack, CLAUDE.md §3, so
  it's a plain `<pre>` with syntax highlighting at most, or nothing fancier).
  `templates/admin/login-audit.html` — separate page per PRD §24.9's sub-nav ("Audit Log" is one
  entry, but §18.3 describes one table with `entity`/`action` columns that write-audit rows have
  and login-audit rows don't — confirm one page with a tab/toggle vs. two separate pages before
  building).
- Sidebar entry — `/admin/audit-log` (or whatever path is chosen), Admin-only, following
  `/admin/notifications`'s `sec:authorize="hasRole('ADMIN')"` pattern in
  `fragments/sidebar.html`.

### Tests

- `AuditListener` integration test: a compensation update (or any tracked write) produces an
  `audit_entry` row with correct before/after JSON, actor, and tenant.
- Login audit test: successful and failed login attempts both produce a `login_audit` row; an
  unknown email produces one with `user_id = null`.
- RBAC: one 200 test (Admin) + one 403 test (Employee, Manager) per new endpoint, matching
  `AdminNotificationsAccessControlTest`'s shape (sub-phase 1.10).
- Tenant isolation: `TenantIsolationTestBase`-based test proving tenant A cannot see tenant B's
  audit rows (or, if this phase decides Super Admin needs cross-tenant visibility, that only Super
  Admin's own separate security realm can).
- CI grep test for "no secrets/tokens/PII in logs" (CLAUDE.md §6 A09) — confirm whether one already
  exists before adding a duplicate.

## Definition of Done for Phase 1.11

- Every write operation this phase scopes in produces an `audit_entry` row with correct
  before/after JSON, actor, tenant, and timestamp.
- Every login attempt (success and failure, known and unknown email) produces a `login_audit` row.
- Admin can filter and view both logs, including a before/after diff view for a write.
- `./mvnw verify` green, ArchUnit green, no new exemptions.

## Not in scope for Phase 1.11 — do not start any of this

- Retention/archival to cold storage (PRD §18.4) — explicitly Phase 2.
- CSV export of the audit log (PRD §18.3) — explicitly Phase 2.
- Super Admin's own audit trail of impersonation actions (PRD §26 "Impersonate Admin
  (audit-logged)") — that's Phase 1.13 (Super Admin console) wiring into this phase's
  `AuditListener`/`audit_entry`, not something to build ahead of the console that triggers it.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, this phase. Now
  unblocked: check ADR 0006 decision B before implementing, since `login_audit` landing this phase
  is exactly what that decision was waiting on.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.**
  Deferred since Phase 1.4. Home's "My Peers" widget (Phase 1.9) deliberately shows names/avatars
  only, with no profile link for a plain Employee viewer, for exactly this reason (ADR 0015).
- **`EmployeeTerminationJob`/`AnnualGrantJob` process tenants serially, not in parallel.** Still fine
  at current scale (CLAUDE.md §11) — revisit only with a benchmark showing a problem. Sub-phase
  1.10's `HolidayReminderJob`/`DailyReminderJob` copy the same serial-fan-out shape for the same
  reason.
- **Manual leave-balance adjustment (`BalanceService.adjustManually`) has no dedicated admin screen**, by design — backend capability, RBAC-tested only. Revisit if a real need surfaces.
- **`AdminEmployeeController`'s write-then-separate-read transaction shape has an open correctness question** (ADR 0009's Context/Consequences) — not investigated.
- **A booking whose computed duration is exactly zero working days is not rejected** (Phase 1.6, ADR 0010's Consequences) — no PRD requirement for a minimum-duration guard.
- **`listPendingForApprover`'s Manager-scope filter runs one `isManagerOf` CTE call per tenant-wide pending request**, not a single batched query (Phase 1.6, ADR 0010's Consequences). Fine at current tenant sizes.
- **`S3FileStorage` does not exist.** `storage.FileStorage`'s `presignedUrl()` seam is ready for it, but no cloud tenant needs it yet (Phase 1.7, ADR 0012 Decision A). The first cloud tenant onboarding is the trigger to build it.
- **`GlobalExceptionHandler`'s `MaxUploadSizeExceededException` handler is untested at the servlet-enforcement level** (Phase 1.7, ADR 0012 Decision D).
- **No orphan-file sweeper** for the harmless-orphan-on-partial-failure cases `documents.CompanyFileService`/`EmployeeDocumentService` accept (Phase 1.7, ADR 0012 Decision C).
- **The iCal feed's scope is the token owner's own approved leave only**, not their team's — FR-6.5's "optionally team's leave" was scoped out of Phase 1.8 with the user's sign-off; revisit only if a real request surfaces (Phase 1.8, ADR 0014).
- **The iCal token is stored raw (unhashed) on `app_user`**, a deliberate deviation from CLAUDE.md §6 A02's reset-token hashing rule — see ADR 0014 before "fixing" this.
- **Team Calendar has no Week view or Grid/List toggle** — PRD §24.5 names both, but Phase 1.8 shipped month-view-only as a documented simplification (the DoD only required a filterable month grid). Revisit if a real need surfaces.
- **No general `/settings` page/shell exists** — Phase 1.8 added exactly one page, `/settings/calendar`, linked directly from the topbar account menu rather than building a multi-tab Settings shell for tabs (Change password, MFA) that don't exist yet. The next feature that needs a Settings tab is the natural trigger to introduce the shell.
- **Home dashboard's Company News widget was dropped in favor of Upcoming Holidays** (Phase 1.9,
  ADR 0015) — PRD §24.2's wireframe still names it; the real feature (Admin-authored posts) stays
  Phase 2 as originally planned. Revisit the grid's sixth slot only if Company News ships for real.
- **For Action's Tasks pane is system-generated only — "Complete your profile," derived, no
  table.** Admin-assigned tasks (the other half of FR-8.4) need a `Task` entity/migration/RLS/
  assignment UI that Phase 1.9's "DB changes: none new" scope explicitly ruled out (ADR 0015). The
  next phase that can afford a new table is the natural owner.
- **`tenant.primary_color` was removed, not implemented.** Sub-phase 1.10 was the trigger to finally
  wire the carried-forward `--bs-primary` injection, but the column had never been used by any code,
  every row held a stale non-brand default, and no Admin editor existed to set it to anything else —
  so it was dropped rather than built (ADR 0016). One Caderly brand color now serves every tenant,
  in the app UI and in email. Do not reintroduce a per-tenant color without a new ADR.
- **An Admin cancelling *someone else's* leave notifies nobody.** `LeaveRequestService.cancel` only
  calls `notifyCancellation` when the acting employee is the requester themselves — PRD §17.2 lists
  only "Leave cancelled *by employee* → Approver," so this is a documented scope boundary, not a
  bug, but the employee whose leave an Admin cancelled is never told. Revisit if a real complaint
  surfaces (sub-phase 1.10).
- **`EmailOutbox.RETRY_BACKOFF`'s third entry (10 minutes) is dead code** given `MAX_ATTEMPTS = 3` —
  the third failure goes straight to `FAILED` before that backoff is ever consulted. Harmless, but
  worth knowing before "fixing" the retry schedule (sub-phase 1.10).
- **`documents.EmployeeDocument` still has no expiry-date column.** Sub-phase 1.10's "Document
  expiring" event (PRD §17.2) reads `government_id.expiry_date` instead — that column already
  existed and FR-3.7 already promised it. If a future requirement needs expiry tracking on
  *uploaded files* specifically, that is new scope (ADR 0016).
- **No plain-text `multipart/alternative` part on outbound email** — `EmailDelivery`'s
  `MimeMessageHelper` is built non-multipart, HTML-only. Every current mail client target renders
  HTML fine; revisit only if a real plain-text-only recipient surfaces (sub-phase 1.10).

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.10 → 1.11 update is the template).
3. Commit `phase-1.11-audit-log` and open a PR against `main`.
4. Do not start the next phase in the same session.
