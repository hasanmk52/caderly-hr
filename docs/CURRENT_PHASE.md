# Current Sub-Phase

**Working on:** Phase 1.9 — Home Dashboard + For Action inbox
**Branch:** `phase-1.9-home-dashboard` (not yet created — create it before writing any code)
**Goal:** The Home page (`/`) becomes a real dashboard of htmx-loaded widgets — not just the "Welcome" banner and balance cards it has today — matching the PRD's wireframe. The "For Action" inbox itself (pending approvals + tasks) already shipped in Phase 1.6; this phase's remaining scope is the Home page widgets, plus whatever "For Action" polish, if any, a closer read of PRD §6.8/§24.6 turns up.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.9 Home Dashboard + For Action inbox" section under Phase 1 — MVP
2. `docs/Caderly_PRD.md` — §24.2 (Home Dashboard wireframe, the widget list), §8 feature #13 (a shorter, possibly more authoritative widget list — see the open discrepancy below), §6.8 FR-8.1–8.4 (For Action — likely already fully satisfied by Phase 1.6's `LeaveApprovalController`/`for-action.html`, but confirm rather than assume), §24.6 (For Action wireframe, to confirm nothing there is still open)
3. `CLAUDE.md` — §4 (package structure — this phase is aggregation/read-only across `people`, `timeoff`, `documents`; decide whether it needs its own package or stays inline in `web.HomeController` given how small it is), §6 A01 (RBAC — Home is already `isAuthenticated()`-equivalent via `hasRole('EMPLOYEE')`, confirm the new widgets need no additional role gating)
4. `docs/UI_Guidelines.md` §8.2 (Home dashboard widgets — 3/2/1-column responsive grid, each widget a `card` with `card-header` title + optional `card-footer` "View all" link, **independently htmx-loaded on page load via `hx-get`/`hx-trigger="load"`**, and explicitly: "Never render more than 6 widgets by default")

## Open discrepancy to resolve before planning — do not silently pick one

PRD §24.2's full wireframe lists **7** widgets: Welcome, Book Time Off, My Peers, Time Off Today, My Days Off, Company News (static MVP tile), Resources (top 3 company files). PRD §8's high-level MVP feature list (feature #13) names only **4**: "book time off, my peers, time off today, upcoming holidays" — a materially different, shorter set that also swaps in "upcoming holidays" for the last slot rather than My Days Off/Company News/Resources. UI Guidelines §8.2's "never more than 6 widgets" caps whichever list wins, but doesn't resolve which widgets make the cut. Read both sections closely (not just the excerpts here) and either reconcile them or ask — don't default to the longer list just because it's more detailed.

## Already in place — do not redo

- **`web.HomeController` + `templates/home.html`**: `GET /` already renders "Welcome, {FirstName}!" and the "Book Time Off" balance-card widget (`BalanceService.listCurrentYearForEmployee`), gracefully degrading to a name-free "Welcome!" with no cards for a principal with no linked Employee (Admin-only dev accounts). This is two of the wireframe's widgets already done — extend this controller/template, don't replace it.
- **For Action (Phase 1.6)**: `web.LeaveApprovalController`, `templates/for-action.html` — Tasks/Time-off-requests tabs, Pending/Completed sub-tabs, Approve/Reject with note modals, Manager-scope vs Admin-org-wide visibility (PRD FR-8.2/8.3). Confirm FR-8.4's "system-generated tasks" (e.g. "Complete your profile") exist; if not, that's this phase's job per the Implementation Plan's "For Action inbox" wording, not a Phase 2 deferral — check before assuming.
- **Team Calendar (Phase 1.8)**: `calendar.CalendarService`/`TimeoffFacade`/`PeopleFacade` — the "Time Off Today" widget (who's out today, tenant-wide) and "My Peers" widget's "Out today" tab almost certainly want to reuse `TimeoffFacade.listApprovedLeaveInRange(today, today, ...)` rather than a new query; check before adding a parallel one. `calendar` package's `package-info.java` currently says its only consumer is the team-calendar grid/feed — update that Javadoc if Home becomes a second consumer.
- **Files (Phase 1.7)**: `documents.CompanyFileService.listAll()` — the "Resources" widget (if it survives the discrepancy above) is "top 3 company files," a thin slice of this, not new upload/storage logic.
- **Sidebar `Home` link**: already enabled and the default landing page; no sidebar change needed.

## Remaining Phase 1.9 work (pending the discrepancy above)

### Backend

- Per-widget data for whichever widgets the resolved list includes: My Peers (same-department + same-manager peers, `PeopleFacade` needs a query for this — check whether one already exists before adding), Time Off Today / Upcoming Holidays (`TimeoffFacade`), My Days Off (`TimeoffFacade.listAllApprovedLeaveForEmployee`-shaped, upcoming-only), Company News (MVP: a static "Welcome to Caderly" tile per PRD §24.2 — no backend needed), Resources (`documents.CompanyFileService`).
- Per UI Guidelines §8.2, each widget loads independently: likely one `GET /widgets/<name>` htmx-fragment endpoint per widget rather than one big `home()` method assembling everything server-side up front — confirm this against how `AdminLeaveController`'s or similar existing htmx-fragment endpoints are shaped before inventing a new pattern.

### Frontend

- `templates/home.html`: extend into the 3/2/1-column responsive widget grid (UI Guidelines §8.2), each widget its own `card` with `hx-trigger="load"`.
- Widget partials/fragments per widget, each with its own empty state (UI Guidelines §7.1) — e.g., "My Peers" with none, "My Days Off" with nothing upcoming.

### Tests

- Widget renders empty state gracefully (Implementation Plan's own testing note) — one test per widget's empty-data path.
- RBAC: confirm no new role gate is needed (Home is tenant-member-visible, no PRD §26 row for it beyond being signed in) — write the test proving it rather than assuming.

## Definition of Done for Phase 1.9

- Home page matches whichever widget list the discrepancy above resolves to, each independently htmx-loaded, none exceeding UI Guidelines §8.2's 6-widget cap.
- Every widget has a designed empty state — no bare "no results" per UI Guidelines §7.1.
- `./mvnw verify` green, ArchUnit green, no new exemptions.

## Not in scope for Phase 1.9 — do not start any of this

- Notification event wiring (birthday/anniversary/document-expiry reminders that might feed a widget) — Phase 1.10. If a widget needs this data, read it directly rather than waiting on the notification system.
- Company News beyond the MVP static tile (Admin-authored posts) — explicitly Phase 2 per PRD §24.2.
- Org tree view toggle on People (PRD §8.3 UI Guidelines, unrelated to Home) — still Phase 2, not touched by this phase.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.** Deferred since Phase 1.4; still not this phase's job (even though "My Peers" widget is adjacent — the widget shows names/avatars, not full profile access).
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

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.8 → 1.9 update is the template).
3. Commit `phase-1.9-home-dashboard` and open a PR against `main`.
4. Do not start the next phase in the same session.
