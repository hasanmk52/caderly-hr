# ADR 0010 — Leave requests: approval authority model, balance-integrity guard, and weekend-bitmask convention

**Status:** Accepted
**Date:** 2026-08-18
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0007 (combined-transaction controller methods), ADR 0009 (`people`↔`timeoff` event wiring)

---

## Context

Phase 1.6 (`docs/CURRENT_PHASE.md`) adds `leave_request`: an Employee books time off, their Manager
(or Admin, if no manager) approves/rejects it, approval debits `leave_balance.used`, cancellation
credits it back, and termination auto-cancels future requests. Several design points aren't fully
pinned down by the PRD text alone.

**1. PRD §26's permissions legend and BR-2 read as two different scopes for "who may approve".**
§26's matrix marks Manager approval authority as "👥 direct + indirect reports" (transitive). BR-2
("Manager transitivity") says the MVP routes notifications to the *direct* manager only, escalating
to Admin — explicitly deferring a chain-walking escalation to Phase 2. Read together, these aren't
contradictory: one is about who gets *notified* when a request is submitted, the other about who is
*authorized to decide* it once submitted.

**2. M7 (post-1.5 review finding).** `BalanceService.adjustManually` could already push `granted`
below `used` — dormant because `used` stayed at zero through 1.5, but this phase is what first
increments it via approval, making the gap reachable.

**3. `Tenant.weekendDays`'s stored comment ("Sat=64, Sun=32 -> 96") has zero consumers before this
phase.** Something has to actually decode the bitmask now that the duration algorithm needs it, and
the comment's labeling doesn't uniquely fix a bit-to-day mapping without picking a convention.

**4. `LeaveBalance` is keyed by a single `year`; the PRD's schema and duration algorithm are both
silent on a request whose `start_date`/`end_date` straddle Dec 31 → Jan 1.**

## Decision

### A. Routing (who gets notified) stays direct-manager-only; approval authority (who may decide) is transitive

- `LeaveRequestService.book(...)` resolves exactly one notification target per PRD §12.4 step 2:
  the requester's direct manager if set, else every active tenant Admin. No chain-walking on
  submit — this is BR-2's MVP scope, verbatim.
- `LeaveRequestService.requireApprovalAuthority(...)` (called from `approve`/`reject`) accepts an
  Admin unconditionally, or any manager for whom `PeopleFacade.isManagerOf(deciderEmployeeId,
  requesterId)` is true — the same transitive, recursive-CTE query `EmployeeService.getProfileForViewer`
  already uses to decide profile-view access. This satisfies §26's "direct + indirect" authority
  without building new org-chart logic: an indirect manager who wasn't emailed can still act if they
  open the For Action page and see the request (visible to them because `listPendingForApprover`
  uses the same `isManagerOf` check for scoping the list itself).
- **BR-6 (no self-approval) is checked first, unconditionally**, before either branch above — so an
  Admin deciding their own request is blocked exactly like a stranger would be. This is what makes
  self-approval impossible by construction rather than by convention (CURRENT_PHASE.md/CLAUDE.md
  §6 A04's threat-modeling expectation): `RequireApprovalAuthority` never reaches the `isAdmin`
  check at all if `request.employeeId().equals(deciderEmployeeId)`.

### B. M7: reject the adjustment, backstopped by a DB constraint

`BalanceService.adjustManually` throws `ValidationException("LEAVE_BALANCE_BELOW_USED", ...)` if
`newGranted < used`. `leave_balance` also gets `CHECK (granted >= used AND used >= 0)`
(`V202608171000`) as the same app-layer-plus-DB-layer pattern CLAUDE.md §5/§6 already uses
elsewhere (Hibernate `@TenantId` plus Postgres RLS). Chosen over silently allowing a negative
`remaining()` or allowing-with-a-warning: rejecting outright is the only option that doesn't require
the Home/profile balance cards to render a new "over-drawn" state this phase doesn't otherwise need.

### C. Weekend bitmask: `bit = 1 << (DayOfWeek.getValue() - 1)`, Mon=1..Sun=7 (`java.time`'s own ordering)

`LeaveDurationCalculator.decodeWeekend(int bitmask)` adopts this convention: Mon=1, Tue=2, Wed=4,
Thu=8, Fri=16, Sat=32, Sun=64. The stored default `96` (`32 + 64`) decodes to {Saturday, Sunday}
under this scheme — matching the field's own comment's *intent* ("weekend") even though the
comment's literal "Sat=64, Sun=32" labels the two bits backwards relative to this convention; since
nothing read the bitmask before this phase, there was no existing behavior to stay compatible with,
only the stored integer value, which resolves the same either way for the default. Unit-tested
against both the default (96 → Sat+Sun) and a custom Friday+Saturday tenant (16+32=48 → Fri+Sat,
proving the region-agnostic requirement in PRD §1/§12.5 actually works, not just the default case).

### D. Cross-year bookings are rejected, not split across two balance years

`LeaveRequestService.book(...)` throws `ValidationException("LEAVE_CROSS_YEAR_NOT_SUPPORTED", ...)`
if `start.getYear() != end.getYear()`. Building dual-balance-row splitting for a Dec→Jan request
is real complexity the PRD never asks for (§12.6's "not doing" list is silent here, but the
`LeaveBalance` schema itself — one row per `(employee, leave_type, year)` — was clearly not designed
to represent a split), and CURRENT_PHASE.md's "keep it simple" instruction disfavors it as a
speculative feature.

## Consequences

**Positive:**
- No new org-chart-traversal code: `EmployeeRepository.isManagerOf`'s existing recursive CTE serves
  both the pre-existing profile-view-access check and this phase's approval-authority check,
  proven by `LeaveRequestServiceTest.approve_byIndirectManager_succeedsViaTransitiveAuthority` and
  the RBAC test `LeaveApprovalAccessControlTest.approve_asUnrelatedManager_returns403` (the actual
  gap this phase closes — before booking/approval existed there was no "manager of *this specific*
  employee" boundary to test).
- M7 closed with the same two-layer pattern (app guard + DB `CHECK`) the rest of the codebase
  already uses for tenancy, rather than inventing a new integrity-enforcement idiom.
- The weekend-bitmask decision is unit-tested against a non-default tenant, so the "region-agnostic"
  requirement is verified, not just assumed from the default.

**Negative / open:**
- A Dec→Jan booking is simply refused for now — an employee wanting time off spanning the New Year
  must submit it as two separate requests. Acceptable at MVP scale; revisit only if a real tenant
  hits it (CLAUDE.md §11: no optimizing before there's a problem).
- The `isManagerOf` check runs once per pending request when a Manager (not Admin) loads For Action
  (`listPendingForApprover`'s filter is O(n) CTE calls, n = tenant-wide pending count, not just
  the manager's own team). Fine at current tenant sizes named in the PRD (§1: a handful of pilot
  companies); would need a single batched query if a tenant's pending-request volume ever made this
  visible.
- A booking whose computed duration is exactly zero (e.g., a range that lands entirely on weekend
  days) is not rejected — `book()` has no minimum-duration guard. Not a named PRD requirement and
  not hit by any real user flow (the UI's live preview shows "0 working days" before submit), so
  left as-is rather than added speculatively.

## Alternatives considered

**1. Escalate to the manager's manager, then Admin, matching §26's legend literally at submit time
too.** Rejected: BR-2 explicitly scopes MVP notification routing to direct-manager-only with no
escalation chain, and CURRENT_PHASE.md's "Phase 2" note for chain-walking notification is direct
enough to not need re-litigating here.

**2. Allow `adjustManually` to push `granted` below `used`, rendering a negative `remaining()` as a
deliberate "over-drawn" state.** Rejected per Context point 2 — would require new UI treatment on
both the Home cards and the Profile Time Off tab this phase doesn't otherwise need, for a case with
no product requirement asking for it.

**3. Decode the weekend bitmask as `Tenant.weekendDays`'s comment literally states (Sat=64,
Sun=32).** Considered, but "Sat=64,Sun=32" doesn't specify a general bit-to-day *function* — only
two example values — so it doesn't actually constrain the decode for arbitrary custom bitmasks
(e.g., Friday+Saturday) any more than the convention chosen here does. `1 << (DayOfWeek.getValue() -
1)` was picked because it is the more conventional/derivable rule (Monday-indexed, matching
`java.time.DayOfWeek`'s own ordinal), not because the alternative was wrong.

## References

- PRD §12.3 (duration algorithm), §12.4 (approval flow), §12.5 (per-tenant configurability), §21
  (`leave_request`/`leave_balance` DDL), §26 (permissions matrix + legend), BR-2/3/4/6/8/9/11
- CLAUDE.md §5 (tenancy — app-layer + DB-layer pattern precedent), §6 A04 (self-approval threat
  model), §8 (state-machine/duration-algorithm TDD), §10 (state-transition recipe), §11 (no
  speculative features / no premature optimization)
- ADR 0009 (`people`↔`timeoff` event wiring — the same pattern this phase's `EmployeeTerminatedEvent`
  copies for the termination-cascade close-out)
- `EmployeeRepository.isManagerOf`, `EmployeeService.getProfileForViewer` (the pre-existing
  transitive-manager query this phase's approval-authority check reuses)
- CURRENT_PHASE.md's carried-forward M7 finding (the full option (a)/(b)/(c) framing this ADR
  resolves as (a))
