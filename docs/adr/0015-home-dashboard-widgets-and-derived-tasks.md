# ADR 0015 — Home dashboard widget list, and a derived (no-table) "Complete your profile" task

**Status:** Accepted
**Date:** 2026-09-09
**Deciders:** Hasan (solo dev)
**Relates to:** CLAUDE.md §4 (package structure), §12 (dependency approval), §5/§6 (access control); PRD §6.8, §8, §24.2, §24.6

---

## Context

Sub-phase 1.9 (`docs/CURRENT_PHASE.md`) turns the Home page from a bare greeting + balance cards
into the real widget dashboard PRD §24.2 wireframes, and closes out PRD FR-8.4 ("Tasks" on For
Action) to whatever extent the phase's own scope note — "DB changes: none new," Complexity S,
0.5 week — actually allows. Two decisions had to be made before either could be planned.

### 1. Which widgets ship

PRD §24.2's wireframe lists seven items: Welcome, Book Time Off, My Peers, Time Off Today, My Days
Off, Company News, Resources. PRD §8 feature #13's shorter MVP list names only four: "book time
off, my peers, time off today, upcoming holidays" — a materially different last slot. UI
Guidelines §8.2 caps the grid at 6 widgets, which doesn't by itself say which list wins.

The two lists reconcile once "Welcome" is set aside: it was already shipped, pre-1.9, as the page's
`<h1>` greeting (`HomeController.home()`), not a card. That leaves §24.2 with exactly **six** card
candidates — Book Time Off, My Peers, Time Off Today, My Days Off, Company News, Resources — which
lands precisely on the 6-widget cap with no room left for §8's Upcoming Holidays.

### 2. FR-8.4 tasks

FR-8.4: "Tasks in MVP are limited to system-generated (e.g., 'Complete your profile') and manually
assigned by Admin. Full checklist templates in Phase 2." No `Task` entity exists. The
Admin-assigned half needs one — a table, a migration, RLS, and an assignment UI — which the phase's
own "DB changes: none new" / Complexity S sizing rules out regardless of appetite.

## Decision

**Widgets:** ship Book Time Off, My Peers, Time Off Today, My Days Off, Upcoming Holidays, and
Resources. **Company News is dropped**, and Upcoming Holidays (PRD §8) takes its slot.

- Company News's MVP form is a static, zero-data "Welcome to Caderly" tile — the real feature
  (Admin-authored posts) is explicitly Phase 2 per §24.2 itself. A tile with no per-tenant data
  behind it earns a grid slot worse than a widget backed by a query that already exists
  (`TimeoffFacade.listPublicHolidaysInRange`, already powering the team calendar's holiday
  columns since Phase 1.8).
- This keeps every widget in the shipped set backed by real, already-modeled data, and still hits
  UI Guidelines §8.2's cap exactly — no seventh widget, no rationing among the six.

**Tasks:** ship only the system-generated half, computed at read time with **no new table**.
"Complete your profile" is derived every page load from the signed-in employee's own blank
self-service fields (`EmployeeForms.SelfProfilePatch`: phone, address line 1, city, country,
postal code) via a new `EmployeeService.incompleteSelfServiceFields` method. There is nothing to
persist, nothing to mark done — the task simply stops appearing once the fields are filled in.
Admin-assigned tasks (the half that genuinely needs a table) carry forward to Phase 2.

`address_line2` is deliberately excluded from the tracked set: it is legitimately blank for most
real addresses (there usually is no line 2), so counting it would make the task permanently
unclearable for the majority of employees who filled in everything they actually have.

Because "complete your profile" applies to every employee, not just Managers/Admins, For Action's
class-level `@PreAuthorize` widens from `hasRole('MANAGER')` to `isAuthenticated()`
(`web.LeaveApprovalController`), and the Tasks pane is now visible to everyone. The Time off
requests pane — approvals — stays gated `sec:authorize="hasAnyRole('MANAGER','ADMIN')"` in the
template, and every mutation endpoint (`approve`, `reject`, `reject-form`) keeps its own
method-level `@PreAuthorize("hasRole('MANAGER')")`, which overrides the class level and is
unaffected by the widening. The sidebar's For Action link is un-gated to match.

## Consequences

**Positive:**
- Every shipped widget and the derived task are backed by data that already exists — zero new
  tables, zero new dependencies, in line with the Implementation Plan's own scope note.
- `calendar.CalendarService.buildTeamCalendar` gets a second consumer (`web.HomeController`'s Time
  Off Today widget) for free — same "who's out in this range" query the team calendar already
  runs, with `from == to == today`. That package's Javadoc is updated to name the new consumer.
- The "My Peers" query (`EmployeeRepository.findPeers`: same department OR same manager, excluding
  self and terminated employees) is the one genuinely new query this phase adds.
- Employees get real, visible value from For Action for the first time — previously the page
  404'd/403'd them entirely.

**Negative / accepted trade-off:**
- The derived task can never be "dismissed" independently of actually completing the profile —
  acceptable, since completing the profile *is* the intended resolution, not a distraction to be
  swiped away.
- Widening `/for-action` to `isAuthenticated()` is a real access-control change, even though its
  only new grant is "see your own derived task" — tracked with its own RBAC test matrix
  (`LeaveApprovalAccessControlTest`) rather than assumed safe.
- PRD §24.2's wireframe text still lists Company News; this ADR is the record of why the shipped
  grid diverges from it, so a future reader hits this file rather than re-litigating the call.

## Alternatives considered

**1. Ship all seven §24.2 widgets, ignoring the 6-cap.** Rejected outright — UI Guidelines §8.2 is
explicit ("never render more than 6 widgets by default") and not this phase's call to override.

**2. Keep Company News's static tile and drop Upcoming Holidays instead.** Rejected: a zero-data
tile provides less real value per grid slot than a widget with actual per-tenant data, and §8's
MVP feature list treats "upcoming holidays" as a named, intended feature — dropping it silently in
favor of static filler text would contradict that list rather than reconcile it.

**3. Build the full FR-8.4 Task entity now (table + migration + Admin assignment UI).** Rejected:
directly contradicts the Implementation Plan's "DB changes: none new" / Complexity S sizing for
this sub-phase. Revisit when a phase is scoped for it.

**4. Leave For Action Manager/Admin-only and skip Tasks entirely this phase.** Considered and
rejected after discussion: the system-generated half of FR-8.4 costs nothing (no table, one
derived query) and delivers a feature every employee — not just approvers — can see immediately.
Deferring it further would have meant re-opening the same page's access control again later for no
added cost saved now.

## References

- `web.HomeController` (six `/widgets/*` fragment endpoints), `templates/home.html` /
  `templates/home/widgets.html`
- `people.EmployeeService#incompleteSelfServiceFields`, `people.EmployeeForms.SelfProfilePatch`
- `people.EmployeeRepository#findPeers`, `people.PeopleFacade#listPeers`
- `web.LeaveApprovalController` (widened class-level `@PreAuthorize`), `templates/for-action.html`
- `calendar.CalendarService#buildTeamCalendar` (Time Off Today widget's data source)
- `docs/Caderly_PRD.md` §24.2 (annotated with this ADR where the shipped grid diverges from the
  wireframe), §8 feature #13, §6.8 FR-8.4
- `docs/UI_Guidelines.md` §8.2 (the 6-widget cap this reconciles against)
