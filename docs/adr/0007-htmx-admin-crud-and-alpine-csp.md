# ADR 0007 — Alpine's CSP build, and the transaction shape for htmx admin CRUD

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0004 (native Hibernate multi-tenancy, `TenantSessionVariableListener`)

---

## Context

Phase 1.3 (Divisions & Departments) is the first screen to actually use Alpine.js and the first
to do htmx-driven mutations that re-render part of the page in the same response. Two problems
surfaced that are not specific to this screen and will recur in every future htmx admin CRUD
screen (1.4 Employee CRUD, 1.6 Leave Requests, etc.), so the decisions are recorded here rather
than re-derived per screen.

**1. Alpine.js needs `'unsafe-eval'`.** `SecurityConfig`'s CSP deliberately omits it (see that
class's own doc comment). The first `x-data` usage broke exactly as predicted: every Alpine
expression failed with `EvalError: ... violates ... 'unsafe-eval' is not an allowed source`,
confirmed in the browser console against the running app.

**2. A write, followed by a read to re-render the page, is not automatically consistent.**
`AdminOrganizationController`'s mutation endpoints call a `@Transactional` service method (the
write), then separately call `divisions.listActive()` / `departments.listActive()` (the read) to
build the response. With the write and the read as two separate, unrelated transactions, this
was reproducible: a genuinely-committed write would render as an empty table in that same
response, though a plain follow-up GET always showed the correct data.

**This was root-caused, not just theorized.** Temporary diagnostic logging was added to
`TenantSessionVariableListener.afterBegin` (tenant ID, a JDBC connection identity marker, and
`TransactionExecution.isNewTransaction()`), and the bug was reproduced under it. The log showed
`afterBegin` firing correctly for the write's transaction (`DivisionService.create`,
`isNew=true`, correct tenant) — and then **never firing again** for the read that immediately
followed in the same request, on the same thread. Since `TenantSessionVariableListener` sets
Postgres's `app.tenant_id` via `SET LOCAL` (transaction-scoped — it dies with the transaction it
was set in, per that class's own comment), a second transaction that never re-triggers the
listener runs with `app.tenant_id` unset. RLS's `USING (tenant_id::text =
current_setting('app.tenant_id', true))` then denies every row by design — no error, just an
empty result. That is the exact, previously-unexplained symptom.

*Why* the read's transaction doesn't (re-)trigger the listener — whether it's specific to how
Spring Data's per-repository-method transactions interact with `TransactionExecutionListener`,
versus an explicit `@Transactional` — was not investigated further. That would mean changing
`TenantSessionVariableListener` itself, which is on the CLAUDE.md §12 ask-first list given its
role in tenant isolation; the fix below avoids needing to touch it by never creating the
two-transaction situation in the first place.

## Decision

### A. Swap the `alpinejs` webjar for `alpinejs__csp`, not the CSP directive

`pom.xml` depends on `org.webjars.npm:alpinejs__csp` (same `alpinejs.version` property, since
Alpine publishes matching versions of both builds) instead of `org.webjars.npm:alpinejs`.
`layout.html`'s script tag points at `/webjars/alpinejs__csp/dist/cdn.min.js`. This is the CSP
build Alpine's own project ships for exactly this situation: a pre-parsed expression evaluator
that never calls `eval`/`new Function`, so it runs under a `script-src` with no `'unsafe-eval'`.

`SecurityConfig` itself does not change. No entry on the CLAUDE.md §12 ask-first list is touched.

Known limitation of the CSP build (from Alpine's own docs, not yet hit in practice): magic
properties and some dynamic expression forms are restricted. If a future screen needs something
the CSP build can't parse, that is a real constraint to design around, not a bug — do not
loosen the CSP as the fix.

### B. A dedicated service-layer class owns the "write, then read everything back" transaction — never the controller

CLAUDE.md §7 is explicit: `@Transactional` belongs on service methods, never on controllers. The
first attempt at this fix put `@Transactional` directly on `AdminOrganizationController`'s
mutation methods, which does close the two-transaction gap — but it both violates that rule and
creates a second, well-known Spring problem: `DivisionService.create()` throws `ConflictException`
on a duplicate name, and the controller catches it inline to reject the field and re-render the
form (the same pattern `AdminUserController` already uses). Once the controller and the service
share one transaction, an exception thrown by an inner `@Transactional` method marks that whole
transaction rollback-only **even though the caller catches it**, so committing later fails with
`UnexpectedRollbackException`.

The actual fix: a new `@Service` class, `OrganizationAdminService`, sits between the controller
and `DivisionService`/`DepartmentService`. Its methods are `@Transactional` and each does exactly
what one controller response needs — a write followed by a fresh read of both Divisions and
Departments (`OrganizationSnapshot`), or, for GET endpoints that read more than one thing (the
full page, and the Department edit form's division dropdown), just the combined read. It never
catches the `CaderlyException`s `DivisionService`/`DepartmentService` throw — they propagate
straight out to the controller. Because nothing swallows the exception *inside* the transaction,
Spring's default behavior (roll back cleanly, rethrow) applies with no special annotation needed:
`DivisionService`/`DepartmentService`'s write methods are plain `@Transactional`, exactly as
before this investigation started.

`AdminOrganizationController` has no `@Transactional` anywhere. GET endpoints that only need one
read (`new-form`, division `edit-form`) call `DivisionService`/`DepartmentService` directly.

**A second, related finding while verifying this:** relying on `SimpleJpaRepository`'s own
implicit per-method transaction — i.e. a `DivisionService`/`DepartmentService` read method with
no `@Transactional` of its own, calling straight through to the repository — was *not* safe
either, even as the only transactional call in a request. The Department "new" form's division
dropdown came back empty every single time (not intermittently) until `listActive()` and
`require()` on both services were given their own explicit `@Transactional(readOnly = true)`.
So the rule is not "one read per request is safe" — it's "every read needs an explicit
`@Transactional` somewhere in the service layer's call chain, full stop." Both services' read
methods now have it.

**Pattern for the next htmx CRUD screen:** if a controller needs a write followed by a read (or
multiple reads) to build its response, give it (or share) a small `@Service`-layer class that
does the whole sequence in one `@Transactional` method and hands back plain data. Don't catch a
service's domain exceptions inside that method — let them propagate to the controller, same as
today.

## Consequences

**Positive:**
- Alpine works under the existing strict CSP with no weakening, and the fix is reusable
  unchanged by every future screen that adopts Alpine.
- The underlying mechanism (RLS's `SET LOCAL` not surviving into a second, untriggered
  transaction) is now confirmed by a log-backed reproduction, not a guess — see Context above.
- `@Transactional` stays out of every controller, matching CLAUDE.md §7 and the rest of the
  codebase. `DivisionService`/`DepartmentService` are unchanged from their original, simplest
  form — no `noRollbackFor`, no special-casing.
- `OrganizationAdminService` is a small, easily-copied template for 1.4's Employee CRUD and any
  later screen with the same write-then-render shape.

**Negative / open:**
- *Why* `TenantSessionVariableListener.afterBegin` doesn't re-fire for that second, independent
  transaction was not root-caused at the framework-internals level — only confirmed as the
  mechanism. If a future screen has a write-then-read need it genuinely cannot combine into one
  service-layer transaction (e.g., a long-running or cross-request flow), that investigation
  needs to happen for real at that point.
- Every future htmx CRUD screen needs to remember this pattern (service owns the transaction,
  controller stays thin) rather than it being structurally enforced. Nothing currently stops a
  future controller method from doing an un-combined write-then-read and silently reintroducing
  the bug; it would need to be caught by testing (or a future ArchUnit rule) rather than the
  compiler.

## Alternatives considered

**1. Add `'unsafe-eval'` to the CSP.** Rejected outright — `SecurityConfig`'s own comment already
names this as the wrong fix, and it would weaken a real control (CLAUDE.md §6 A05) for every page,
not just this one.

**2. Drop Alpine from Phase 1.3, use plain `maxlength` instead of the live character counter.**
Considered and presented to the user as the simpler option; the CSP-build swap was chosen instead
since CURRENT_PHASE.md's stated goal for 1.3 was explicitly to be "the first sub-phase where the
UI does ... an Alpine offcanvas form," and the CSP build satisfies that with no compromise.

**3. `@Transactional` directly on the controller, with `noRollbackFor = CaderlyException.class` on
the service methods to avoid `UnexpectedRollbackException`.** This was the first working fix and
is functionally equivalent to Decision B in terms of closing the two-transaction gap. Rejected
after further discussion: it violates CLAUDE.md §7 outright, and `noRollbackFor` is a sharp tool
that's only safe as long as every write method throws strictly before mutating a row — an
invariant nothing enforces if a future method is added carelessly. `OrganizationAdminService`
achieves the same result without either problem.

**4. Wrap only the read (`populateTables`) in its own fresh `TransactionTemplate`-based
transaction from within the controller, leaving the controller otherwise non-transactional.**
Programmatic transaction demarcation in a controller doesn't violate the letter of CLAUDE.md §7
(no `@Transactional` annotation), but it doesn't honor the spirit of keeping transaction
boundaries in the service layer either. Decision B's dedicated service class does the same job
more idiomatically.

## References

- PRD §13 (Organization Management), §21 (`division`/`department` schema)
- CLAUDE.md §7 (transactions on service methods only), §12 (ask-first list — `SecurityConfig`,
  `TenantSessionVariableListener`)
- ADR 0004 (`@TenantId`, `TenantIdentifierResolver`, `TenantSessionVariableListener`)
- `docs/UI_Guidelines.md` §14 (Alpine.js conventions)
- Implementation Plan §1.3
