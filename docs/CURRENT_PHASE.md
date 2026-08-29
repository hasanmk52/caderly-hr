# Current Sub-Phase

**Working on:** Phase 1.8 — Team Calendar
**Branch:** `phase-1.8-team-calendar` (not yet created — create it before writing any code)
**Goal:** A grid view of the whole team's approved time off (rows = employees, columns = days, colored bars per leave type, filterable by department/division/leave type), plus a per-user tokenized iCal feed URL an employee can subscribe to in Google Calendar/Outlook and see their own approved leave show up automatically.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.8 Team Calendar" section under Phase 1 — MVP
2. `docs/Caderly_PRD.md` — §6.6 (FR-6.1–6.5: team calendar view, filters, iCal feed), §9.4 (US-CAL.3, iCal subscription story), §24.5 (Calendar page UI: header/grid/filter panel), §24.10 (Settings → Calendar integration tab, iCal URL + copy button), AC-CALENDAR.1 (acceptance criterion: subscribe → within 24h Google Calendar shows APPROVED leaves as all-day events), §23 (REST: `GET /api/v1/calendar/team`, `GET /api/v1/calendar/ical.ics?token=`)
3. `CLAUDE.md` — §4 (package structure — no `calendar` package is listed yet; decide where `CalendarService` lives, most likely a new package, and add it to CLAUDE.md §4 if so), §6 A07 (iCal endpoint is intentionally public/no-session, authenticated by a per-user token instead — this is a deliberate exception to "every controller method needs a role check," not an oversight; make the token itself a 32-byte `SecureRandom` value per CLAUDE.md §6 A02's token-generation rule, and decide whether it needs to be stored hashed like password-reset tokens or can be looked up directly — the iCal use case (a long-lived, repeatedly-fetched URL) differs from a one-time reset token, so don't copy that pattern without checking whether it actually fits)
4. `docs/UI_Guidelines.md` — check §8.4 "Team Calendar" (already named in the guidelines) for the grid/filter-panel conventions this phase should follow, and the empty-state pattern for "no leave in this period"

## Already in place — do not redo

- **Everything through 1.7**: full tenancy/auth/org/people/leave/files-and-documents stack. `leave_request` rows with `status = APPROVED` are exactly the data this phase renders — no new leave-domain logic needed, this phase is a read/projection layer over what 1.6 already built.
- **`timeoff.LeaveRequestRepository`**: has the query shapes (`findAllByEmployeeIdOrderBySubmittedAtDesc` etc.) to model `CalendarService.buildTeamCalendar`'s query after — check whether a new tenant-wide "approved leave in a date range, optionally filtered by department/division/leave type" query method is needed, or whether an existing one composes.
- **`org.OrgFacade`**: already exposes department/division lookups for filter-panel dropdowns — reuse it, don't duplicate a query into `calendar` (or wherever this phase's package lands).
- **Sidebar `Calendar` link**: currently a `disabled` stub in `templates/fragments/sidebar.html` (same pattern the Files link was in before Phase 1.7 enabled it) — this phase turns it into a real link, same as 1.7 did for Files.
- **Files & Documents (Phase 1.7)**: `storage.FileStorage`/`documents.*` are unrelated to this phase; nothing here should touch them. ADR 0012 documents the S3 deferral and upload-validation shape for that phase, not this one.

## Remaining Phase 1.8 work

### Schema

- `user_ical_token` table, or a token column on `app_user` (PRD phrases it as "or" — decide which, and write an ADR if the choice isn't obvious from existing precedent). Tenant-scoped if a new table; check RLS applies correctly either way — a public, no-session endpoint reading this data still needs the token lookup itself to resolve the correct tenant context (`TenantContext.runAsSystem` plus an explicit tenant-scoped query, similar to how `PasswordResetToken` lookups work today — check that precedent first).

### Backend

- `CalendarService.buildTeamCalendar(from, to, filter)` — filter by department, division, and/or leave type (PRD §24.5); returns an employee-day matrix of approved leave.
- iCal feed endpoint `GET /api/v1/calendar/ical.ics?token=<token>` — public, no `@PreAuthorize` role check (token-authenticated instead — this is the deliberate exception CLAUDE.md §6 A01 anticipates for public endpoints: "Public endpoints satisfy this with `permitAll()` rather than by omission"). Returns the token owner's own approved leave (and possibly their team's, per FR-6.5 — check the exact scope) as `VEVENT`s. Check whether an iCal-generation library already exists as a dependency or needs to be added (CLAUDE.md §12: new dependency needs a stop-and-ask).
- Token generation/rotation: decide whether the token is user-generated on first visit to Settings → Calendar integration, or provisioned at invite time.

### Frontend

- Calendar page (PRD §24.5): month/week selector, Today button, filter panel (department/division/leave type), Grid/List view toggle, rows = employees with colored bars per leave type, tooltip on hover.
- Settings → Calendar integration tab (PRD §24.10): shows the unique iCal URL with a copy-to-clipboard button.
- Enable the sidebar's `Calendar` link.

### Tests

- iCal output validates against the format (check for an existing validator dependency, or hand-verify against RFC 5545's minimum required fields — don't add a new dependency without checking CLAUDE.md §12 first).
- Grid renders correctly across month boundaries (a leave request spanning the last/first day of a month, and a request entirely outside the visible range).
- Token-based auth: a request with a missing/invalid/revoked token gets a clear 4xx, not a 500 or a silent empty calendar (which would look like "no leave" rather than "bad token").
- Tenant isolation: the token itself must resolve to exactly one tenant/employee — a raw-JDBC RLS test proving a token can't be used to read another tenant's calendar, matching every prior phase's isolation-test shape.
- RBAC: the team calendar page requires `isAuthenticated()` per PRD §26 ("View team calendar ✅ all" — Employee, Manager, and Admin all get full visibility, no role gate beyond being signed in); the iCal feed is the one deliberate exception to a role check, authenticated by token instead — confirm this is a conscious design choice, not a gap, before writing the RBAC test for it.

## Definition of Done for Phase 1.8

- Priya subscribes to her iCal URL in Google Calendar and sees her approved leave appear as an all-day event (PRD's own DoD wording — verify this manually against a real Google Calendar subscription if feasible, not just RFC-shape correctness).
- The Calendar page renders a month grid of the whole tenant's approved leave, filterable by department/division/leave type.
- A request with a bad or missing iCal token gets a clear error, not a 500.
- Tenant isolation proven for the token-to-calendar-data path specifically (this is the one new endpoint this phase adds that isn't behind normal session auth, so it needs its own isolation proof, not just inherited confidence from RLS).
- `./mvnw verify` green, ArchUnit green with no new exemptions (the public iCal endpoint will need a `permitAll()`-equivalent that ArchUnit's `@PreAuthorize`-on-every-method rule still expects — check whether that rule needs a documented, deliberate exemption pattern, or whether the endpoint still carries `@PreAuthorize("permitAll()")` explicitly, matching the login/reset-password precedent in `SecurityConfig`).

## Not in scope for Phase 1.8 — do not start any of this

- Home Dashboard + For Action inbox widgets — Phase 1.9, though it depends on 1.6/1.7/1.8 all being done first.
- Absence Calendar Export (month PDF/CSV, PRD FR-10.4) — a Reports-phase concern (1.12), not this one, even though it sounds calendar-adjacent.
- Notification event wiring for calendar-related events — Phase 1.10.

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.** Deferred since Phase 1.4; still not this phase's job.
- **`EmployeeTerminationJob`/`AnnualGrantJob` process tenants serially, not in parallel.** Still fine at current scale (CLAUDE.md §11) — revisit only with a benchmark showing a problem.
- **Manual leave-balance adjustment (`BalanceService.adjustManually`) has no dedicated admin screen**, by design — backend capability, RBAC-tested only. Revisit if a real need surfaces.
- **`AdminEmployeeController`'s write-then-separate-read transaction shape has an open correctness question** (ADR 0009's Context/Consequences) — not investigated.
- **A booking whose computed duration is exactly zero working days is not rejected** (Phase 1.6, ADR 0010's Consequences) — no PRD requirement for a minimum-duration guard.
- **`listPendingForApprover`'s Manager-scope filter runs one `isManagerOf` CTE call per tenant-wide pending request**, not a single batched query (Phase 1.6, ADR 0010's Consequences). Fine at current tenant sizes.
- **`S3FileStorage` does not exist.** `storage.FileStorage`'s `presignedUrl()` seam is ready for it, but no cloud tenant needs it yet (Phase 1.7, ADR 0012 Decision A). The first cloud tenant onboarding is the trigger to build it — and to revisit whether CLAUDE.md §6a's outbox pattern applies to it, which it deliberately does not for the local-filesystem case.
- **`GlobalExceptionHandler`'s `MaxUploadSizeExceededException` handler is untested at the servlet-enforcement level** (Phase 1.7, ADR 0012 Decision D) — only its own rendering logic has a test. MockMvc's `MOCK` web environment cannot exercise real container-level multipart size limits; a `RANDOM_PORT` + real HTTP client test would close this gap if ever worth the cost.
- **No orphan-file sweeper** for the harmless-orphan-on-partial-failure cases `documents.CompanyFileService`/`EmployeeDocumentService` accept (Phase 1.7, ADR 0012 Decision C). Not needed at current upload volume; revisit with evidence, not preemptively.
- **PRD's "Local and S3 backends both pass the same test suite" testing note (Phase 1.7's own DoD wording) does not apply** — S3 is deferred (see above), so there is only one backend to run the contract test suite against right now. `storage.FileStorageContractTest` is written so a future `S3FileStorageTest` can extend it without rewriting the cases.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.7 → 1.8 update is the template).
3. Commit `phase-1.8-team-calendar` and open a PR against `main`.
4. Do not start the next phase in the same session.
