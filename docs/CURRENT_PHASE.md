# Current Sub-Phase

**Working on:** Phase 1.7 — Files & Documents
**Branch:** `phase-1.7-files-documents` (not yet created — create it before writing any code)
**Goal:** Company-wide files (Admin-uploaded, visible to everyone in the tenant) and per-employee documents (self + Admin uploadable, on the Profile Documents tab that's been a stub since Phase 1.3), backed by a storage abstraction that can be local filesystem today and S3-compatible later without an application-code change.

## Read these before doing anything

1. `docs/Caderly_Implementation_Plan.md` — the "1.7 Files & Documents" section under Phase 1 — MVP
2. `docs/Caderly_PRD.md` — §6.7 (FR-7.1–7.5: Company Files, Employee Documents, 25 MB limit, allowed types, storage abstraction), §9.5 and §24.7 (Files UI), §6.3.9 (employee-document data model, if present — check the exact field list), CLAUDE.md §6 A03 ("File upload: MIME + magic-byte check (Apache Tika) + extension whitelist + size limit. No exec-able types")
3. `CLAUDE.md` — §4 (package structure: `documents` for `CompanyFile`/`EmployeeDocument` entities, `storage` for the `FileStorage` interface + `Local`/`S3` impls — two new packages, not one), §5 (tenancy — both new entities are tenant-scoped, same RLS/`@TenantId` template every prior phase used), §6 A03 (upload validation, verbatim above), §11 ("Storing files in the database. Files go through `FileStorage`." — an explicit anti-pattern already named)
4. `docs/UI_Guidelines.md` — check for a Files/Documents-specific pattern (table + upload button per §6.7's FR-7.1 description) or whether this phase establishes one
5. Skim `docs/adr/0008-employee-column-encryption.md` for the nearest precedent on handling sensitive-ish employee data (documents may include ID scans) — not a direct pattern match, but the same "what does this data need beyond CRUD" question applies

## Already in place — do not redo

- **Everything through 1.6**: full tenancy/auth/org/people/leave stack. `people/profile.html`'s Documents tab currently renders a stub empty-state (`ProfileController.populateTab`'s `"documents"` case is intentionally a no-op, commented as waiting on this phase) — this is what makes it real.
- **`identity`/`people` upload-adjacent precedent**: nothing uploads a file anywhere yet in this codebase. `EmployeeForms`/`AdminEmployeeController`'s multipart CSV upload (`PublicHolidayService.bulkUpload`, Phase 1.5) is the closest existing example of a `MultipartFile` endpoint — read it for the controller-side shape, but note it doesn't persist the file itself (just parses it), so it's not a template for `FileStorage`.
- **`CLAUDE.md` §6a's durable-outbox pattern** — likely NOT applicable here: a file upload's persistence (DB row + storage write) isn't the same shape as an external async side effect like email. Confirm before assuming either way; do not force-fit the outbox pattern onto file storage without checking whether the PRD or a consistency requirement actually calls for it.

## Remaining Phase 1.7 work

### Schema + entities

- Flyway migration for `company_file` and `employee_document` (exact columns per PRD §6.3.9/§6.7 — check for both, the two may have different shapes). Tenant-scoped: RLS template + `@TenantId`, `rls_probe` grant added to the test isolation-probe migration.
- `CompanyFile`/`EmployeeDocument` entities in a new `documents` package (CLAUDE.md §4) — do not put them in `people` even though `EmployeeDocument` is employee-scoped; the package structure names `documents` explicitly.
- Neither entity stores file bytes (CLAUDE.md §11) — only metadata (name, size, MIME type, storage key/path, uploaded-by, uploaded-at). The actual bytes go through `FileStorage`.

### Backend

- `storage.FileStorage` interface + `LocalFileStorage` + `S3FileStorage` (CLAUDE.md §4). Check `application.yml`/`application-*.yml` for whether a storage-backend selection property convention already exists, or this phase establishes one.
- Multipart upload endpoint(s): MIME + magic-byte validation via Apache Tika (already a listed dependency in CLAUDE.md §3 — check `pom.xml` for whether it's already added or needs one, following §7's "version in `<properties>`" rule if not), extension whitelist (PDF/PNG/JPG/DOCX/XLSX/PPTX per FR-7.4), 25 MB size limit (FR-7.3, "configurable" — check whether that means per-tenant or a static app property), reject executables explicitly even if disguised by extension (the magic-byte check is what catches this).
- Download: presigned URL for S3, streaming response for local (FR-7.5).
- Company Files: Admin uploads, every tenant member can view/download (FR-7.1) — check the exact `@PreAuthorize` shape against PRD §26 if it lists Files there.
- Employee Documents: self + Admin can upload to a given employee's Documents tab (FR-7.2, mirrors the self-or-Admin pattern `people.EmployeeService`'s emergency-contacts/government-IDs already established — reuse that shape, don't invent a new one).

### Frontend

- Files (company) page: table (name, size, uploaded-by, uploaded-at, download) + upload button, Admin-only upload but tenant-wide visibility.
- Profile → Documents tab: now real — upload control + list, self and Admin can upload, replacing the stub `ProfileController` currently renders.

### Tests

- Upload rejected for disguised/wrong-extension executables (`.exe`, `.js`, a `.pdf` that's actually an `.exe` by magic bytes) — CLAUDE.md §6 A03 names this explicitly.
- Upload over the size limit returns a clear error (413 or an inline validation message — decide and be consistent with the rest of the app's error-handling convention, GlobalExceptionHandler's pattern).
- Local and S3 backends both pass the same test suite (PRD's own testing note: "test profile toggles impl") — check whether this needs a Testcontainers S3-compatible mock (e.g., MinIO) or a different strategy; do not assume Testcontainers is the answer without checking what's already available.
- Tenant isolation for both new tables, mirroring every prior phase's `TenantIsolationTestBase` shape.
- RBAC: Company Files upload is Admin-only; Employee Documents upload is self-or-Admin; download/view permissions per whatever §26 (or common sense if §26 is silent) specifies.

## Definition of Done for Phase 1.7

- Admin uploads a handbook PDF to Company Files; any signed-in employee can see and download it.
- Priya (an Employee) uploads a passport scan to her own Documents tab; an Admin can also see/upload to her Documents; another Employee cannot.
- A `.exe` renamed to `.pdf` is rejected (magic-byte check working, not just extension).
- A file over the size limit is rejected with a clear message, not a generic 500.
- Both `company_file`/`employee_document`: RLS + `@TenantId` + passing tenant-isolation tests.
- `./mvnw verify` green, PMD at zero violations, ArchUnit green with no new exemptions.

## Not in scope for Phase 1.7 — do not start any of this

- Document-expiry reminder emails (PRD FR-9.2 lists "document expiry (30/14/7 days before)" as a notification event) — that's Phase 1.10's job (notification event wiring), not this phase's, even though it directly concerns data this phase creates.
- Tasks / Action Inbox beyond what 1.6 already built for leave requests (PRD §6.8) — a separate phase concern (1.9 names "Home Dashboard + For Action inbox" as when Tasks-as-a-concept gets real UI); this phase's Documents tab work is unrelated.
- Team Calendar, Reporting — later phases (1.8, 1.12).

## Carried forward — open items

These were accepted deviations, not oversights. Do not silently "fix" them; they have owners.

- **Lockout is keyed on the user, not (email + IP)** — blocked on `login_audit`, Phase 1.11. ADR 0006 decision B.
- **Password-reset enumeration safety is response-shape only**, not constant-time. ADR 0006 decision E.
- **No common-password blocklist.** ADR 0006 decision F.
- **Tenant primary colour not yet injected into `--bs-primary`.** Phase 1.10 owns tenant branding.
- **Peer-to-peer profile viewing (PRD §26 "View peer profile 🔒 basic") is not implemented.** Deferred since Phase 1.4; still not this phase's job.
- **`EmployeeTerminationJob`/`AnnualGrantJob` process tenants serially, not in parallel.** Still fine at current scale (CLAUDE.md §11) — revisit only with a benchmark showing a problem.
- **Manual leave-balance adjustment (`BalanceService.adjustManually`) has no dedicated admin screen**, by design — backend capability, RBAC-tested only. Revisit if a real need surfaces.
- **`AdminEmployeeController`'s write-then-separate-read transaction shape has an open correctness question** (ADR 0009's Context/Consequences) — not investigated. If this phase adds any mutation to `AdminEmployeeController` itself, follow the combined-transaction shape instead of extending the unconfirmed pattern further.
- **A booking whose computed duration is exactly zero working days is not rejected** (Phase 1.6, ADR 0010's Consequences) — no PRD requirement for a minimum-duration guard, and the live preview shows "0 working days" before submit. Not this phase's concern; noted in case a future phase's UI work touches the Book Time Off modal and wants to revisit it.
- **`listPendingForApprover`'s Manager-scope filter runs one `isManagerOf` CTE call per tenant-wide pending request**, not a single batched query (Phase 1.6, ADR 0010's Consequences). Fine at current tenant sizes; revisit only with a benchmark showing a problem.

## When you finish

1. Confirm every DoD item above with a specific test or command result — do not claim done from vibes.
2. Update this file to whatever sub-phase comes next (this file's 1.6 → 1.7 update is the template).
3. Commit `phase-1.7-files-documents` and open a PR against `main`.
4. Do not start the next phase in the same session.
