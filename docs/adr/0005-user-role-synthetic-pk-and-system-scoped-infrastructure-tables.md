# ADR 0005 — `user_role` synthetic PK, and system-scoped infrastructure tables

**Status:** Accepted
**Date:** 2026-07-23
**Deciders:** Hasan (solo dev)
**Supersedes portions of:** PRD §21 (original `user_role` schema; original `email_outbox` / `audit_entry` / `login_audit` implied treatment)

---

## Context

Two closely-related schema decisions surfaced during Phase 1.2 planning that PRD §21 (as originally written) did not resolve cleanly:

**1. `user_role` shape.** PRD §21 gave `user_role` a composite primary key `(user_id, role)` plus a `tenant_id` column. This shape is fine on paper, but Hibernate 7 native multi-tenancy (`@TenantId`, as adopted in ADR 0004) is easiest to apply to entities that have a single UUID `id` primary key — the same shape as `TenantAwareEntity`. Modelling `user_role` with a composite key means it either can't extend `TenantAwareEntity` (violating the §5.1 "every tenant-scoped entity extends `TenantAwareEntity`" rule by construction) or requires an entity-specific exception with hand-written `@TenantId` field placement.

**2. Where `email_outbox`, `audit_entry`, `login_audit` sit relative to the tenancy model.** These tables carry a `tenant_id` column but are read and written by system-level code (background dispatchers, entity listeners) that runs without a `TenantContext`. If they were treated as tenant-scoped ORM entities with `@TenantId`, Hibernate would refuse their queries in the dispatcher / listener path (no current tenant → no results). The `tenant_id` on these tables is a *reference* for downstream branding and Admin-viewer filtering, not the RLS discriminator that gates access.

## Decision

**A. `user_role` gets a synthetic UUID PK.**
- Column shape: `id uuid PK, tenant_id uuid NOT NULL, user_id uuid NOT NULL, role varchar(20) NOT NULL, created_at, updated_at`.
- `UNIQUE (tenant_id, user_id, role)` preserves the semantic uniqueness the composite PK was expressing.
- The Java entity extends `TenantAwareEntity`, receives `@TenantId` automatically per §5, and behaves like every other tenant-scoped table (RLS, Hibernate-armed filter, `TenantContext.runAsSystem` bypass for Super Admin).
- Repository: `UserRoleRepository extends TenantAwareRepository<UserRole>` (the base interface takes a single type parameter and fixes the ID type to `UUID`).

**B. `email_outbox`, `audit_entry`, `login_audit` (and `tenant`, `super_admin`) are system-scoped infrastructure tables, not tenant-scoped.**
- Java entities live in a `.system` sub-package of their module (e.g. `notifications.system.EmailOutbox`, `audit.system.AuditEntry`).
- They extend `BaseEntity` (id + timestamps), **not** `TenantAwareEntity`.
- No `@TenantId` annotation. No RLS policy on the underlying tables. Postgres `tenant_id` column, where present, is nullable and serves as a reference for Admin-viewer filtering and per-tenant branding lookup.
- Background jobs (`EmailDispatcher`, audit archival) query these directly, with **no per-tenant ORM filter** on the entity. They must still establish system mode — see "Correction" below.
- Any Admin viewer of these tables **must** explicitly filter `WHERE tenant_id = :currentTenant` in the controller/service (or via a Spring Data method) — defense in depth, since the ORM won't enforce it.
- ArchUnit's "every `@Entity` in a tenant-scoped package extends `TenantAwareEntity`" rule is scoped by package: entities in `.system` sub-packages are exempted by pattern, not by class allowlist. This makes the exemption self-documenting: putting an entity under `.system` is an explicit design statement.

## Consequences

**Positive:**
- One consistent shape for every tenant-scoped table (single UUID PK + `TenantAwareEntity`). Reduces per-entity thinking.
- `user_role` picks up `@TenantId` + RLS + `runAsSystem` bypass automatically. No `user_role`-specific tenancy code.
- The "system-scoped infrastructure" category is now explicit and rule-enforced (ArchUnit + `.system` package convention), removing the "why doesn't `email_outbox` extend `TenantAwareEntity`?" recurring question.
- The `EmailDispatcher` polls all `PENDING` rows across tenants in one query, with no per-tenant ORM filter to defeat and no RLS policy to satisfy.

**Negative:**
- `user_role` has a synthetic PK that doesn't appear anywhere else in the business logic. The `UNIQUE (tenant_id, user_id, role)` constraint is the semantic key, so any code that upserts by "user+role" must query on those three columns, not on `id`. Cheap tax.
- Two mental models for "tenant-tagged tables": (1) tenant-scoped domain entities with hard enforcement, (2) system infrastructure tables with soft filtering. The `.system` package convention makes this cheap to remember, but it is a distinction that has to be taught to anyone new to the codebase.
- PRD §21 is now the source of truth for shape; anyone reading the older wording elsewhere might be confused. Mitigated by editing §21 in the same PR as this ADR.

## Alternatives considered

**1. Keep `user_role` composite PK, exempt it from §5.1.** Rejected: creates a per-entity carve-out and forces hand-written `@TenantId` field placement + custom repository shape. Every future reviewer would ask "why is this different?"

**2. Model roles as `@ElementCollection` on `AppUser`.** Rejected in the plan-time question that produced this ADR: (a) can't be `@TenantId`-decorated because it isn't an entity, so tenancy would fall back to hand-written checks or pure RLS; (b) the "give me all Admins in tenant X" query — needed for approver fallback in Phase 1.6 — is awkward through a collection table.

**3. Treat `email_outbox` as tenant-scoped (`@TenantId` + RLS) and have the dispatcher pick a tenant per poll.** Rejected: a dispatcher that must iterate tenants to drain one queue is strictly worse than one cross-tenant query, and RLS on the table would mean a mis-set session variable silently drains nothing. Per-tenant ORM filtering buys no security here — the admin viewer needs an explicit tenant filter regardless. Note this is *not* the same question as whether the dispatcher calls `runAsSystem`; see the correction below.

## Correction (2026-08-02, during Phase 1.2 implementation)

The original draft of this ADR claimed the dispatcher needs "no `runAsSystem` gymnastics". **That is wrong**, and the error is worth recording because it is easy to repeat.

`TenantSessionVariableListener` is a `TransactionExecutionListener` registered on the **transaction manager**, not on any entity or repository. Its `afterBegin` runs for *every* transaction in the application and calls `TenantContext.require()` unless `TenantContext.isSystem()`. It has no idea which entities the transaction will touch.

So on a `@Scheduled` thread — which has neither a tenant nor the system flag — the transaction fails at begin with `CannotCreateTransactionException` caused by `IllegalStateException: No tenant in context`, **before a single row of `email_outbox` is read**. Making the entity system-scoped does not help, because the failure happens above the ORM.

`EmailDispatcher` must therefore wrap its work in `TenantContext.runAsSystem("email dispatch", ...)`. This is cheap and safe: system mode skips the `set_config` call entirely, and `email_outbox` has no RLS policy to be denied by. It also means the bypass is logged, satisfying CLAUDE.md §5 rule 6.

Decision B itself is unaffected — `email_outbox` stays system-scoped, extends `BaseEntity`, and has no `@TenantId` and no RLS. Only the "no `runAsSystem` needed" consequence was incorrect.

**General rule this establishes:** any code that opens a transaction off the request thread — schedulers, `ApplicationRunner`s, `@PostConstruct`, test fixtures — must run under `runAsSystem` or set an explicit tenant, whatever entities it touches.

## References

- PRD §5 (Multi-tenancy contract)
- PRD §21 (Database Schema — updated as part of this ADR)
- CLAUDE.md §5 (Multi-tenancy contract, non-negotiable)
- CLAUDE.md §6a (Reliability contract — durable outbox)
- ADR 0004 (Native Hibernate 7 multi-tenancy)
- Implementation Plan §1.1 (ArchUnit rule with `.system` exemption)
- Implementation Plan §1.2 (`email_outbox` as system-scoped, `user_role` as synthetic-PK tenant-scoped)
