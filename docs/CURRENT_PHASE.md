# Current Sub-Phase

**Working on:** Phase 1.15 — Deployment & Ops
**Branch:** `phase-1.15-deployment` (not yet created — create it before writing any code)
**Goal:** MHZ's instance runs on a real VPS: Docker Compose (app + reverse proxy only, Postgres
native on the host), automatic TLS, nightly backups, and an install guide a solo engineer can
follow end-to-end without guessing.

## Phase 1.14 — Dashboard Customization (complete)

**Branch:** `phase-1.14-dashboard-customize`
**Goal:** Every user can Customize the Home dashboard: drag or arrow-button reorder, hide/show,
Save / Cancel / Reset. Per-user, persisted. **Design record:** ADR 0021.
**Verified:** `identity.DashboardLayoutResolutionTest` (pure rule), `identity.DashboardLayoutServiceTest`
(save/reload/reset, cross-tenant and cross-user isolation), `web.DashboardLayoutAccessControlTest`
(200 per role, anonymous redirected, CSRF required), `e2e.DashboardCustomizeE2ETest` (reorder, hide,
save, reload, cancel, reset; hidden card carries no `hx-get`), ArchUnit tenant-entity rule
(default-deny pattern already covers `DashboardLayout`), full `./mvnw verify`.

## Phase 1.13 — Super Admin Console (complete)

**Branch:** `phase-1.13-superadmin`
**Goal:** Hasan (the one Super Admin) can provision a new tenant + first Admin in one form
submit, suspend/delete a tenant, and impersonate a tenant's Admin for support — all from a
separate `/superadmin` console with its own authentication realm and an IP allowlist.
**Design record:** ADR 0019 (`docs/adr/0019-superadmin-console-realm-and-impersonation.md`) —
the dual-realm security design, why ADR 0003/0004's deferred `BYPASSRLS` work turned out not to
be needed, the two vulnerabilities found and fixed during review, the impersonation ticket
mechanism, MFA deferral, and the htmx redirect gotcha.

## Definition of Done for Phase 1.13 — all complete, verified below

- **Hasan can create a new tenant + first Admin in one form submit and log in as that Admin
  afterward.** Verified: `superadmin.TenantProvisioningServiceTest` (create tenant + first Admin
  in one transaction, invite email queued to `email_outbox`) and
  `superadmin.SuperAdminTenantControllerTest` (the console's `create()` endpoint end-to-end via
  MockMvc). Login-afterward is the pre-existing accept-invite flow (Phase 1.2/1.4), reused
  unmodified — no new login path was needed.
- **Suspending a tenant blocks login for every user in it; unsuspending restores it.** Verified:
  `tenant.TenantResolutionFilterTest#home_whenSuspendedTenant_returns503` and
  `security.AuthenticationFlowTest#login_whenTenantIsSuspended_isRejectedBeforeAuthentication` —
  the suspension check runs in `TenantResolutionFilter`, before the security chain, so a suspended
  tenant never reaches a password check at all, not merely gets rejected after one.
- **Deleting a tenant is soft (grace period), not an immediate hard delete.** Verified:
  `tenant.TenantServiceTest` asserts `softDelete` sets `deleted_at` and
  `TenantFacade.bySlug`/`findAnyAdmin`-style lookups return empty for a soft-deleted tenant
  afterward; no hard-delete code path exists anywhere in this phase's diff.
- **Impersonating an Admin works, is clearly indicated in the UI while active, and every write
  made during it is audited with `actor_role=SUPERADMIN_IMPERSONATING`.** Verified:
  `identity.ImpersonationServiceTest`/`web.ImpersonationControllerTest` (ticket mint/redeem,
  session established, `SessionRegistry` registration, audit start/end events with the correlated
  `entity_id`) and a live `GET /admin/users` returning `200` mid-session proving `@PreAuthorize`
  behaves as the real Admin. The in-session banner is the impersonation-banner fragment added to
  the tenant layout, confirmed rendered in the controller test's returned HTML.
- **`./mvnw verify` green, ArchUnit green, no new exemptions beyond what `superadmin`'s existing
  ArchUnit carve-out already allows.** Verified: full suite green throughout (661/661 at Task 6's
  completion per the phase ledger; re-confirmed via `./mvnw -q -o test` and `./mvnw -q -o verify`
  at Task 7 close-out, both exit 0, zero new ArchUnit exemptions).

Design record for all of the above, including two vulnerabilities found and fixed during review
(cross-realm session isolation; a percent-encoding bypass of the IP allowlist/rate limiter) and
the reasoning for not needing ADR 0003/0004's deferred `BYPASSRLS` work: **ADR 0019**
(`docs/adr/0019-superadmin-console-realm-and-impersonation.md`).

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.15 Deployment & Ops" section under Phase 1 —
   MVP.
2. `docs/Caderly_PRD.md` — whatever sections cover deployment/ops expectations (backup cadence,
   TLS, the "PostgreSQL is external" architecture decision already locked in CLAUDE.md §3).
3. `CLAUDE.md` — §3 (locked stack: Docker + docker-compose for the app + reverse proxy only,
   PostgreSQL is external/host-native, distroless JRE 25 base image — do not containerize
   Postgres), §6 A05 (security headers, actuator exposure, no default passwords — all must hold
   in the shipped Docker image), §6 A08 (Docker base image pinned by digest, not just tag).

## Already in place — do not redo

- **Distroless JRE 25 base image and Docker + docker-compose as the deployment mechanism** are
  already locked in CLAUDE.md §3 — this phase builds the actual `Dockerfile`/`docker-compose.yml`,
  it does not decide the approach.
- **`application.yml`'s env-var-driven configuration** (DB URL, DB user/password, SMTP,
  `CADERLY_ENCRYPTION_KEY`, `CADERLY_SUPERADMIN_EMAIL`/`PASSWORD`, `caderly.superadmin
  .ip-allowlist`, etc.) — every secret this phase's `.env.example` needs to document already
  exists as an env-var-backed property from earlier phases; this phase does not invent new
  secrets, it documents and wires the existing ones for a real deployment.
- **Actuator already restricted per CLAUDE.md §6 A05** (`/actuator/env`, `/actuator/heapdump`
  disabled in the prod profile) — this phase's Prometheus scrape endpoint work builds on that
  existing posture, it does not loosen it.
- **The Super Admin IP allowlist's deployment requirement is already documented** next to
  `caderly.superadmin.ip-allowlist` in `application.yml` (Phase 1.13, ADR 0019 decision A): the
  reverse proxy must **set**, not append, `X-Forwarded-For`, and the app port must be bound to
  loopback. The Caddyfile this phase writes must satisfy that requirement for the whole app, not
  just the Super Admin console.

## Remaining Phase 1.15 work

### Infra
- Multi-stage `Dockerfile`, distroless JRE 25 base image pinned by digest (CLAUDE.md §6 A08).
- `docker-compose.yml` with `caderly-app` + `caddy` only — no Postgres service; the app connects
  to the host's native Postgres 17 (`host.docker.internal:5432` or the host's LAN IP).
- `Caddyfile` with automatic TLS for `*.caderly.app` (or the chosen domain).
- Nightly `pg_dump` cron on the host, uploaded off-site to S3.
- `.env.example` documenting every env var this app already reads (DB URL/user/password, SMTP,
  encryption key, Super Admin bootstrap credentials, IP allowlist) — no new secrets, just
  documentation of what already exists.
- `INSTALL.md` for the MHZ Debian VPS: Postgres 17 via `apt`, `caderly` role + database,
  `pg_hba.conf` for local + Docker-bridge access, then `docker compose up -d`.
- Prometheus scrape endpoint (via the already-present Actuator, exposed per CLAUDE.md §6 A05's
  management-port + auth rule).
- Wildcard DNS setup guide.
- Optional Kubernetes/cloud variant documented separately (same image, external managed Postgres
  — RDS/Cloud SQL/Neon — same env vars, no code change).

### Tests
- `docker compose up` on a fresh Debian box (after Postgres is installed + DB created) works
  end-to-end.
- Restore-from-backup drill documented and run once.

## Definition of Done for Phase 1.15

- MHZ's instance runs on a $10/mo Hetzner VPS: Postgres 17 native, Docker running `caderly-app` +
  `caddy`, wildcard TLS working for the chosen domain (e.g. `mhz.caderly.app`).

## Not in scope for Phase 1.15 — do not start any of this

- Any Phase 2 feature work — this phase is infra-only.
- Kubernetes/cloud-managed-Postgres as the primary deployment target — documented as an optional
  variant only; the MHZ/pilot-tenant target is the Debian VPS + native Postgres shape.

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
- **Super Admin's cross-tenant audit view is still not built** (PRD §26) — Phase 1.13 built the
  console itself (provisioning/suspend/delete/impersonate) but deliberately not a cross-tenant
  audit *viewer* on top of the already-system-scoped `audit_entry` table; that was confirmed
  out-of-scope for 1.13, not merely missed. Still needs an owning phase.
- **A soft-deleted tenant's slug can never be reused** (Phase 1.13, ADR 0019 decision I) —
  `tenant.slug` has a bare `UNIQUE` constraint, no partial index excluding soft-deleted rows. A
  real product-behavior question (should a deleted tenant's slug free up after its grace period?)
  flagged during Task 1, never resolved. Any fix needs a schema change and its own ADR.
- **`ImpersonationService.findAnyAdmin` picks the first ACTIVE Admin found** when a tenant has more
  than one (Phase 1.13, ADR 0019 decision I) — no admin-picker UI was in scope. Revisit if a real
  pilot tenant with multiple Admins needs to choose which one to impersonate.
- **Super Admin login has no MFA** (Phase 1.13, ADR 0019 decision F) — no `TotpService` exists
  anywhere in the codebase yet, for any realm. The IP allowlist + rate limiting are the controls
  for this phase. Revisit once TOTP is built for the tenant realm; wiring it into the Super Admin
  realm at that point is a small addition on top, not a new mechanism.
- **htmx cannot observe a response header on a redirect its own XHR call already followed** (Phase
  1.13, ADR 0019 decision H) — a real, non-obvious gotcha (not specific to Super Admin) worth
  remembering anywhere else in this codebase that pairs `HX-Redirect` with a genuine
  Spring `redirect:`: the fix is a plain `200` fragment response, or a real top-level form
  navigation when the target is cross-origin (CORS blocks the XHR case there).
- **`SuperAdminSecurityConfig`'s Javadoc still misstates Spring's default `SecurityContextRepository`
  composition order** (Phase 1.13) — functionally harmless, comment-only, deliberately left alone
  per CLAUDE.md §12 rather than touched out-of-scope during an unrelated fix round.
- **`superadmin.SuperAdminTenantController`'s `errorDetail()`/`baseUrl()` helpers duplicate logic**
  already in `web.WebMessages`/`web.RequestTenant`, which aren't visible outside their own package
  (Phase 1.13) — reasonable duplication at this scale, not extracted into a shared seam.
- **Tenant-wide default dashboard layout is not built** (Phase 1.14, ADR 0021). Customization is per-user
  only; an Admin-set company default with per-user override was considered and deliberately left
  out. Revisit only if a pilot tenant asks for it.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.14 → 1.15 update is the template).
3. Commit the sub-phase branch and open a PR against `main`.
4. Do not start the next phase in the same session.
