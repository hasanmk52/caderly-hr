# Current Sub-Phase

**Working on:** Phase 1.10 — Notifications: templates, event wiring, admin view
**Branch:** `phase-1.10-notifications` (not yet created — create it before writing any code)
**Goal:** Turn the plain inline-string emails Phase 1.2/1.6 already send (invite, password reset,
leave requested/approved/rejected/cancelled) into a real Thymeleaf-templated, per-tenant-branded
catalog, wire up every remaining event PRD §17.2 names (holiday reminder, birthday, work
anniversary, document expiry), and give Admin a way to see and retry failed sends.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.10 Notifications" section under Phase 1 — MVP
2. `docs/Caderly_PRD.md` — §6.9 (Tasks & Notifications FR-9.1–9.4), §17 (Notifications — channels,
   the full event list in §17.2, delivery in §17.3, per-user digest preference explicitly deferred
   to Phase 2 in §17.4 — don't build it early)
3. `CLAUDE.md` — §6a (durable outbox contract — every event below goes through `email_outbox`,
   never `mailSender.send()` inline), §7 (Thymeleaf email templates live in
   `src/main/resources/templates/email/*.html`; user-facing text still goes through
   `messages.properties` per ADR 0013 — templates are not an exception)

## Already in place — do not redo

- **`notifications.system.EmailOutbox`/`EmailOutboxRepository`/`EmailDispatcher`/`EmailDelivery`**
  (Phase 1.2): the durable outbox itself — write intent row, `@Scheduled` dispatcher, retry with
  backoff, `FAILED` terminal state. This phase extends what gets enqueued, not the dispatch
  mechanism.
- **Invite + password reset emails** (`identity.IdentityEmails`, `identity.InviteService`,
  `identity.PasswordResetService`): already wired, but as inline-built subject/body strings, not
  Thymeleaf templates — check whether migrating them to the new template mechanism is in this
  phase's scope or a nice-to-have; PRD §17.2 lists them as already-satisfied, so don't treat them
  as blocking.
- **Leave lifecycle emails** (`timeoff.TimeoffEmails`, called from `timeoff.LeaveRequestService`):
  requested (to approver), approved/rejected (to employee) already fire on the real business
  actions. Same inline-string caveat as above — confirm cancelled is actually wired (§17.2 lists it;
  a quick grep of `TimeoffEmails`/`LeaveRequestService.cancel` will confirm either way).
- **`EmployeeTerminationJob`/`AnnualGrantJob`** (Phase 1.4/1.5): the two existing `@Scheduled` jobs
  this phase's new ones (holiday reminder, birthday/anniversary, document expiry) should match in
  shape — one call per tenant, `TenantContext.runAsSystem`.

## Remaining Phase 1.10 work

### Backend

- **DB:** extend `email_outbox` only if needed (`event_type` for filtering, `template_key`).
  Additive Flyway migration + RLS unchanged — no non-additive schema change without an ADR
  (CLAUDE.md §12).
- Thymeleaf templates in `src/main/resources/templates/email/*.html`, one per event.
- `EmailTemplateService.render(templateKey, model)` merging per-tenant branding (logo URL, primary
  color, tenant name) — check `tenant.Tenant` for what branding fields already exist before adding
  new ones.
- Wire the events PRD §17.2 names that aren't firing yet: holiday reminder (day-before, whole
  tenant), birthday & work anniversary (to team, per config), document expiry (30/14/7 days
  before, to employee + Admin). Each needs its own `@Scheduled` job or a daily sweep, per
  CLAUDE.md §6a — no `@Async`.
- Admin outbox viewer: `/admin/notifications`, paginated table (date, to, subject, status,
  attempts, last error), filter by status/date, "retry" action for `FAILED` rows.
- Per-tenant notification toggles (disable birthday/anniversary/etc.) — one settings page per the
  Implementation Plan; confirm against PRD FR-9.3 whether this is Admin-only and per-category.

### Frontend

- `templates/admin/notifications.html` (new) — table + filter + retry, matching
  `admin/holidays.html`'s CRUD-grid shape (ADR 0007) as closely as the read-mostly nature of this
  page allows.
- Notification toggle settings page — decide whether this lives under `/admin` (tenant-wide
  config) or `/settings` (the DoD is about tenant-wide categories, so `/admin` is the likely fit;
  confirm before building).

### Tests

- Snapshot/rendering test per template: branding merge correct, no missing tokens/keys.
- Integration test per event: perform the business action (or advance the clock past a
  scheduled-job boundary via `MutableClock`), assert the correct `email_outbox` row exists with
  the correct recipient(s).
- E2E: Admin sees a `FAILED` row after a simulated SMTP outage, clicks retry, row transitions to
  `SENT`.

## Definition of Done for Phase 1.10

- Every event in PRD §17.2 produces a correctly-branded email (verified in MailHog or the outbox
  table, not just "the code compiles").
- Admin can inspect failed sends and retry them from `/admin/notifications`.
- Per-tenant category toggles work: disabling a category stops that event from enqueuing.
- `./mvnw verify` green, ArchUnit green (no `mailSender.send()`/inline HTTP call outside the
  outbox pattern — CLAUDE.md §6a), no new exemptions.

## Not in scope for Phase 1.10 — do not start any of this

- Per-user digest-vs-immediate preference (PRD §17.4) — explicitly Phase 2.
- Slack/MS Teams webhook notifications — Phase 2, needs its own `webhook_outbox` (CLAUDE.md §6a
  already names this as planned, not built).

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** This phase's branding-merge work
  is the natural trigger to finally close this out — check before deferring it again.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.**
  Deferred since Phase 1.4. Home's "My Peers" widget (Phase 1.9) deliberately shows names/avatars
  only, with no profile link for a plain Employee viewer, for exactly this reason (ADR 0015).
- **`EmployeeTerminationJob`/`AnnualGrantJob` process tenants serially, not in parallel.** Still fine at current scale (CLAUDE.md §11) — revisit only with a benchmark showing a problem.
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

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.9 → 1.10 update is the template).
3. Commit `phase-1.10-notifications` and open a PR against `main`.
4. Do not start the next phase in the same session.
