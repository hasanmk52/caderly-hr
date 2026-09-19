# Current Sub-Phase

**Working on:** Phase 1.13 — Super Admin Console
**Branch:** `phase-1.13-superadmin` (not yet created — create it before writing any code)
**Goal:** Hasan (the one Super Admin) can provision a new tenant + first Admin in one form
submit, suspend/delete a tenant, and impersonate a tenant's Admin for support — all from a
separate `/superadmin` console with its own authentication realm and an IP allowlist.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.13 Super Admin console" section under Phase 1
   — MVP.
2. `docs/Caderly_PRD.md` — §6.12 (FR-12.1 through FR-12.5), §23.2's `/superadmin/**` endpoints,
   §26's Super Admin row in the permissions matrix, and §27's architecture diagram for where the
   Super Admin realm sits relative to the main app.
3. `CLAUDE.md` — §12: a separate `SecurityFilterChain` for `/superadmin/**` is exactly the kind
   of change §12 says to stop and ask before making (alongside `SecurityConfig` itself), even
   though it's additive rather than a change to the existing tenant-facing chain. Also §5 rule 6
   (`TenantContext.runAsSystem` for anything that bypasses tenancy — impersonation is the
   textbook case) and §6 A07 (session revocation, MFA) since Super Admin login is a second,
   parallel authentication realm to the tenant one.

## Already in place — do not redo

- **`tenant.Tenant`/`TenantRepository`** — `suspended` (boolean) and `deleted_at` (nullable
  timestamp) columns already exist on the `tenant` table (migration
  `V202607241000__create_tenant_and_super_admin.sql`), unused by any code yet. Phase 1.13 is what
  finally reads/writes them.
- **`super_admin` table** already exists (same migration) — `id`, `email`, `password_hash`,
  `mfa_secret`, `created_at`. No JPA entity, repository, or `UserDetailsService` wired to it yet;
  the `com.caderly.caderlyhr.superadmin` package is currently empty. This table is intentionally
  system-scoped: no `tenant_id`, no RLS (same category as `email_outbox`/`audit_entry`, ADR 0005
  decision B) — a Super Admin is not a member of any tenant.
- **`TenantContext.runAsSystem`** (used already by `EmployeeTerminationJob`, tenant provisioning
  test fixtures, etc.) — the mechanism impersonation's tenant-switch and every cross-tenant Super
  Admin read should reuse, not a new bespoke bypass.
- **`audit` module** (`AuditListener`, `AuditEntry`) — already captures actor id and role on every
  write; FR-12.5's "impersonate with explicit audit log entry" needs a new `actor_role` value
  (`SUPERADMIN_IMPERSONATING` per the Implementation Plan) recorded on every write made while
  impersonating, not a new audit mechanism.
- **Admin-only page/filter patterns** (`admin/notifications.html`, `admin/audit-log.html`,
  `admin/reports*.html` from 1.12) — the tenant list + create form is a good candidate to reuse
  the same filter-form and table conventions, adapted for a cross-tenant (not tenant-scoped) list.

## Remaining Phase 1.13 work

### Backend
- `SuperAdmin` entity (`BaseEntity`, not `TenantAwareEntity` — see "already in place" above) +
  repository + `UserDetailsService` for the new realm.
- Separate `SecurityFilterChain` for `/superadmin/**`, distinct from the tenant-facing one —
  confirm approach before implementing (CLAUDE.md §12).
- IP allowlist filter, configured via env var, guarding the whole `/superadmin/**` chain.
- Tenant provisioning service: create tenant + first Admin (`AppUser` + linked `Employee`?
  confirm whether Super Admin-created Admins need an Employee record or just an `AppUser`) in one
  transaction, sending the same invite-email path `identity`/`people` already use elsewhere.
- Suspend (`tenant.suspended = true`, blocks login tenant-wide — confirm exactly where this is
  checked in the login flow) and delete (`tenant.deleted_at` set, 30-day grace period per FR-12.4
  — confirm what "grace period" means operationally: a scheduled hard-delete job, or just a
  soft-delete that a future job purges; no such job exists yet).
- Impersonation endpoint: spoofs `TenantContext` + `Authentication` for one session, audit entries
  during that session marked `actor_role=SUPERADMIN_IMPERSONATING`.

### Frontend
- Super Admin console shell (separate layout from the tenant app's `layout.html`? confirm — a
  Super Admin is never "in" a tenant, so the tenant-scoped topbar/sidebar don't apply as-is).
- Tenant list + create form + suspend/delete row actions.

### Tests
- Impersonation audit trail correct (`actor_role` value, tenant context during the impersonated
  session, and that it reverts cleanly after).
- IP allowlist enforced (allowed IP gets through, disallowed gets 403/blocked before
  authentication).
- RBAC: `/superadmin/**` unreachable via the tenant-facing `SecurityFilterChain`'s session, and
  vice versa — the two realms must not cross-authenticate.

## Definition of Done for Phase 1.13

- Hasan can create a new tenant + first Admin in one form submit (PRD's own DoD wording) and log
  in as that Admin afterward.
- Suspending a tenant blocks login for every user in it; unsuspending restores it.
- Deleting a tenant is soft (grace period), not an immediate hard delete.
- Impersonating an Admin works, is clearly indicated in the UI while active, and every write made
  during it is audited with `actor_role=SUPERADMIN_IMPERSONATING`.
- `./mvnw verify` green, ArchUnit green, no new exemptions beyond what `superadmin`'s existing
  ArchUnit carve-out already allows.

## Not in scope for Phase 1.13 — do not start any of this

- Any tenant self-service signup flow — Super Admin-only provisioning, per PRD §6.12.
- Billing/subscription management — not named anywhere in PRD §6.12 or the Implementation Plan.
- Per-tenant feature flags — no PRD requirement for this yet.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Reports MVP CSV downloads live under `/admin/reports/*.csv`, not PRD §23.2's literal
  `/api/v1/reports/*.csv`** (Phase 1.12, ADR 0018) — no external API consumer exists yet; revisit
  if one surfaces.
- **Leave Utilization/Headcount aggregation (SUM/COUNT/GROUP BY) lives in `timeoff`/`people`'s own
  repositories, exposed through their facades** — the codebase's first GROUP BY queries (Phase
  1.12, ADR 0018). `reports.ReportService` only joins already-aggregated facade output; it never
  aggregates itself.
- **Headcount report groups historical months by an employee's *current* department**, not their
  department at that historical time — no historical department-assignment tracking exists (only
  `EmployeeManagerHistory` covers manager reassignment). Phase 1.12, ADR 0018. Revisit only if a
  real need for historical department tracking surfaces.
- **Report preview tables (Balance/Utilization/Headcount) have no pagination** — row counts stay
  small at MHZ/pilot-tenant scale (Phase 1.12, ADR 0018). Revisit if a real tenant's row count
  makes that untrue.
- **Lockout re-keying to (email + IP) is now done** (Phase 1.11, ADR 0017) — closes the item that
  was carried forward from Phase 1.2/ADR 0006 decision B. No longer an open item.
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
- **`AppUser.failed_login_count`/`failed_login_window_start` DB columns are dead**, left in place
  rather than risking a destructive drop-column migration (Phase 1.11, ADR 0017) — the lockout
  decision now lives entirely in `identity.LoginAttemptService`, backed by `login_audit`. Drop them
  in a future migration only if a real need to reclaim the columns surfaces.
- **The write-audit Admin viewer shows the actor's raw user id, not their email** (Phase 1.11, ADR
  0017) — resolving it would require the `audit` module to depend on `identity`, which already
  depends on `audit` the other way (the lockout re-key), and that would be a package cycle. Revisit
  only if a real usability complaint surfaces; the fix would be a small `IdentityFacade` read method.
- **Audit diffs do not capture relationship-field changes** (e.g. an employee's manager/department
  reassignment via FK) — only scalar columns are diffed (Phase 1.11, ADR 0017). The one relation
  most likely to matter (manager) already has its own audited history table
  (`people.EmployeeManagerHistory`). Revisit only if a real need for relation-level diffs surfaces.
- **Super Admin's cross-tenant audit view is not built** (PRD §26) — Phase 1.13 builds the console
  itself (provisioning/suspend/delete/impersonate); a cross-tenant audit *viewer* on top of the
  already-system-scoped `audit_entry` table is not named in the Implementation Plan's 1.13 scope
  and should be confirmed as in/out before building it.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.12 → 1.13 update is the template).
3. Commit `phase-1.13-superadmin` and open a PR against `main`.
4. Do not start the next phase in the same session.
