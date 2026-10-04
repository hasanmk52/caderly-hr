# ADR 0021 — Dashboard customization: per-user layout table, SortableJS, hidden widgets unloaded

**Status:** Accepted
**Date:** 2026-10-04
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0015 (home widgets), PRD §24.2, UI Guidelines §8.2, CLAUDE.md §5/§12

---

## Context

Home renders six fixed widgets. Every user should be able to reorder and hide their own, with
the choice persisting across sessions and devices.

## Decision

1. **A `dashboard_layout` table, one row per user** (`user_id` unique FK to `app_user`), not a
   column on `app_user`. It is tenant-scoped with RLS like any other table, is audited by the
   normal entity listener, and keeps UI preference out of the identity row. It is keyed on the
   user, not the employee, so an Admin account with no Employee record works too.
2. **Comma-separated widget keys** in two `varchar` columns (`widget_order`, `hidden_widgets`).
   At most six short keys, never queried by content, so a join table or JSON would add machinery
   for nothing.
3. **`DashboardWidget` enum is the only whitelist.** Stored values are never trusted: resolution
   drops unknown and duplicate keys and appends any widget missing from storage (visible, at the
   end), so a future seventh widget appears for everyone without a data migration.
4. **No row means the registry default.** Reset deletes the row.
5. **SortableJS (WebJar, served from `'self'`)** for drag. Native HTML5 drag-and-drop has no touch
   support and is awkward to keep accessible. CSP is unchanged. Up/down and eye buttons are the
   keyboard alternative (UI Guidelines §9) and are what the E2E test drives.
6. **Hidden widgets render as placeholders with no `hx-trigger="load"`**, so they cost no queries.
   Showing a hidden widget takes effect after Save and reload.
7. **Writes** are `POST /dashboard/layout` and `POST /dashboard/layout/reset`
   (`hasRole('EMPLOYEE')`). The user comes only from the principal, never a request parameter, so
   one user cannot write another's layout. Threat model: the only inputs are widget keys, and
   they are filtered through the enum.

## Consequences

- A tenant-wide default layout is deliberately not built.
- A second preference (for example density) would justify a general `user_preference` table; this
  one stays single-purpose until then.
