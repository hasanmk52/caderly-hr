# Current Sub-Phase

**Working on:** Phase 1.12 — Reports MVP (Balance, Utilization, Headcount + CSV export)
**Branch:** `phase-1.12-reports` (not yet created — create it before writing any code)
**Goal:** Admin can generate each of the three MVP reports, preview it on screen, and download it
as a CSV that opens cleanly in Excel.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.12 Reports MVP" section under Phase 1 — MVP.
2. `docs/Caderly_PRD.md` — the Reports section (balance/utilization/headcount definitions) and
   §23.2's `GET /api/v1/reports/*.csv` endpoints, if this phase ends up exposing them.
3. `CLAUDE.md` — §7 (coding standards: DTOs are records, dependency versions in `<properties>` —
   `com.opencsv` needs a new `<opencsv.version>` property, which is a new Maven dependency and
   therefore on the §12 ask-first list before adding it), §10 recipe "Adding a new REST endpoint"
   if the CSV download goes through `/api/v1` rather than a plain web-layer download.

## Already in place — do not redo

- **`timeoff.LeaveBalance`/`LeaveRequest`/`LeaveType`** (Phase 1.6) and **`people.Employee`**
  (Phase 1.3) carry everything the three MVP reports aggregate over — no new tables expected
  (Implementation Plan's own "DB changes: none" for this phase).
- **`people.PeopleFacade`/`timeoff.TimeoffFacade`** — cross-module reads for a `ReportService` in
  a new `reports` module should go through these facades, not direct repository access (CLAUDE.md
  §4).
- **Admin pagination/filter-form conventions** (`notifications.NotificationAdminService`,
  `audit.AuditAdminService`, sub-phases 1.10/1.11) — the report *preview* table is a good candidate
  to reuse the same prev/next-only pagination shape if a report can return many rows; the report
  *download* is a single CSV response, not paginated.

## Remaining Phase 1.12 work

### Backend
- New `reports` module: `ReportService` (or one service per report) returning DTOs — balance,
  utilization, headcount, each per the PRD's exact field definitions.
- CSV export via `com.opencsv` — new Maven dependency, ask before adding per CLAUDE.md §12, with
  its version declared in `<properties>` per §7's convention.
- Decide the download route shape: a `web` controller endpoint returning `text/csv`, or the
  `/api/v1/reports/*.csv` REST endpoints PRD §23.2 names — confirm which before building both.

### Frontend
- Admin → Reports page (sidebar's "Reports" link is currently a disabled placeholder in
  `fragments/sidebar.html` — this phase is what enables it) with 3 report cards.
- Each card opens a form (filter, date range) + preview table + "Download CSV" button.

### Tests
- Report totals correct against seeded data (unit or integration, per report).
- RBAC: Admin-only, matching the existing `Admin*AccessControlTest` shape.

## Definition of Done for Phase 1.12

- Admin generates each of the three reports, previews it, downloads the CSV, and it opens cleanly
  in Excel (correct headers, no encoding/delimiter issues).
- `./mvnw verify` green, ArchUnit green, no new exemptions.

## Not in scope for Phase 1.12 — do not start any of this

- Any report beyond Balance/Utilization/Headcount (Implementation Plan lists exactly these three
  for MVP).
- Scheduled/emailed report delivery — on-demand generation only.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

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
- **Super Admin's cross-tenant audit view and impersonation audit trail are not built** (PRD §26) —
  no Super Admin console exists yet. Phase 1.13's job, wiring into this phase's `audit` module.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.11 → 1.12 update is the template).
3. Commit `phase-1.12-reports` and open a PR against `main`.
4. Do not start the next phase in the same session.
