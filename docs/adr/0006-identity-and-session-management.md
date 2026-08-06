# ADR 0006 — Identity, authentication, and session management

**Status:** Accepted
**Date:** 2026-08-02
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0004 (native Hibernate multi-tenancy), ADR 0005 (`user_role` shape, system-scoped tables)
**Supersedes portions of:** PRD §21 (`password_reset_token` shape), PRD §19.1 (lockout key)

---

## Context

Phase 1.2 replaces the Phase-0 placeholder `SecurityConfig` with real authentication. `SecurityConfig` is on the CLAUDE.md §12 ask-first list, so the shape is recorded here before any code is written.

Three things had to be settled, and one stack fact had to be corrected.

**Stack correction.** CLAUDE.md §3 and §6 said "Spring Security 6". The project actually resolves **Spring Security 7.1.0** via the Spring Boot 4.1.0 BOM (alongside Spring Framework 7.0.8 and Hibernate ORM 7.4.1 — the same "the docs lag the BOM" pattern ADR 0004 hit). Security 7 removed a large amount of API deprecated through the 6.x line, so this is not a cosmetic difference: configuration written against 6.x guidance will not compile.

**1. How to scope authentication to the resolved tenant.** PRD §19.1 and the Implementation Plan both call for a "custom `AuthenticationProvider` that scopes the user lookup by current tenant", so that a valid email under the wrong subdomain fails as *user not found* rather than leaking that the account exists elsewhere.

**2. Where the lockout counter lives.** PRD §19.1 specifies lockout keyed on (email + IP). `docs/CURRENT_PHASE.md` defers `login_audit` to Phase 1.11 and mandates using `app_user.failed_login_count` in this phase — but that column is per-user and carries no IP dimension.

**3. Session storage and revocation.** PRD §19.1 wants 8 h idle / 24 h absolute timeouts and immediate revocation on password or role change, on a single-instance deployment today with a multi-instance future.

## Decision

### A. No custom `AuthenticationProvider` — a tenant-scoped `UserDetailsService` is sufficient

`AppUserDetailsService` performs `appUserRepository.findByEmail(email)`. Because `AppUser` extends `TenantAwareEntity`, Hibernate's `@TenantId` discriminator (ADR 0004) restricts that query to the tenant `TenantResolutionFilter` already resolved from the subdomain. A valid email belonging to another tenant simply returns no row, and Spring Security raises the same `UsernameNotFoundException` it would for a nonexistent address.

The security requirement is therefore met **by construction**, with no custom provider, no hand-written tenant predicate, and nothing for a future contributor to forget. Stock `DaoAuthenticationProvider` is used unchanged.

Ordering is what makes this safe, and it is already guaranteed: `TenantResolutionFilter` is registered at `Ordered.HIGHEST_PRECEDENCE` and runs before the Spring Security filter chain, so `TenantContext` is populated before authentication begins.

`AppUserDetailsService` lives in the `identity` package and is consumed by `security` only through the framework `UserDetailsService` interface, so no package cycle is introduced.

**Suspended tenants** (PRD §20.7) need no login-path code either: `TenantResolutionFilter` returns 503 for a suspended tenant before the security chain is reached. Phase 1.2 adds a test pinning this behaviour; it does not add a mechanism.

**`UserStatus` maps onto the `UserDetails` flags** — `INVITED` and `DISABLED` map to `enabled = false`, `LOCKED` (and a live `locked_until`) to `accountNonLocked = false` — so `DaoAuthenticationProvider`'s built-in pre-authentication checks reject them. No status branching in application code.

### B. Lockout is keyed on the user, not (email + IP), for Phase 1.2

`app_user.failed_login_count` + `locked_until` implement the 5-failures / 15-minute rule. The IP dimension of PRD §19.1 is covered instead by the Bucket4j login limiter (10 requests/min/IP, PRD §19.7), which is a strictly tighter constraint on any single IP than the lockout rule would be.

This is a deliberate, time-boxed deviation. The (email + IP) key needs a per-attempt record with an IP column — that is `login_audit`, which Phase 1.11 owns. Revisit when it lands.

Counters are maintained by a `LoginAttemptListener` on Spring Security's `AuthenticationSuccessEvent` / `AbstractAuthenticationFailureEvent`. These fire on the request thread, so `TenantContext` is live and the writes are tenant-scoped like any other.

### C. In-JVM sessions; absolute timeout needs a filter; revocation via `SessionRegistry`

- Server-side `JSESSIONID`, `HttpOnly` + `Secure` + `SameSite=Lax`, stored in-JVM. PRD §19.1's documented swap to `spring-session-jdbc` for multi-instance stays a configuration change, so nothing here may depend on the store being local.
- Session fixation protection: new session on authentication.
- **8 h idle** is `server.servlet.session.timeout`. **24 h absolute is not** — Boot's timeout is idle-only, so a small `AbsoluteSessionTimeoutFilter` compares `HttpSession.getCreationTime()` against a 24 h cap and invalidates. Recording this because "set the timeout property" looks like it covers both requirements and does not.
- Revocation on password change and role change goes through `SessionRegistry` + `HttpSessionEventPublisher`, expiring every session for the affected user. Termination (Phase 1.4, BR-11) will reuse the same call.

### D. `password_reset_token` gains `tenant_id` and `BaseEntity` columns

PRD §21 gives it only `id, user_id, token_hash, expires_at, used_at`. CLAUDE.md §5 rule 1 requires every tenant-scoped table to carry `tenant_id NOT NULL` and RLS, so it extends `TenantAwareEntity` and picks up `created_at` / `updated_at` — the same additive adjustment ADR 0005 made for `user_role`.

Token discipline for both invite and reset: 32 bytes from `SecureRandom`, transmitted raw in the email, stored **SHA-256-hashed only** (CLAUDE.md §6 A02), 24 h TTL, single use. A database leak yields no usable tokens.

### E. Enumeration-safety is response-shape only, not constant-time

The forgot-password endpoint always renders the same page with the same message, whether or not the address exists. It does **not** equalise response timing, which would require performing dummy BCrypt work on the miss path.

Called out rather than left implicit: a determined attacker with clean timing data can still distinguish the two paths. The rate limiter (3 requests/hour/email, PRD §19.7) is what makes that impractical to exploit at scale. Revisit if a threat model ever rates account enumeration as high impact.

### F. The password policy enforces composition rules only — no common-password blocklist

PRD §19.1 asks for "blocked-common-passwords list". Sub-phase 1.2 shipped one: a hand-written 134-entry `security/common-passwords.txt` loaded into a static `Set`. It was removed in review, on the grounds that it was security theatre rather than a control:

- 134 entries is far below the threshold where a blocklist changes an attacker's odds. Real corpora start around 10⁵–10⁷ entries.
- The entries it did hold were mostly unreachable anyway. A candidate has already passed ≥10 characters plus upper + lower + digit before the list is consulted, which eliminates `password`, `123456`, `qwerty` and almost everything else a short hand-written list contains.
- Its presence invited the belief that the requirement was met, which is worse than its visible absence.

What remains enforced: ≥10 characters, at least one upper-case letter, one lower-case letter, one digit — each with a specific error message, and each covered by `PasswordPolicyValidatorTest`.

Two honest ways to satisfy the requirement properly, both deferred as decisions in their own right:

1. **Spring Security's `HaveIBeenPwnedRestApiPasswordChecker`** — real breach coverage, but an outbound HTTP call on every password set. That is a third-party dependency in the signup path and a privacy question for an HR product, and under CLAUDE.md §6a its failure mode needs thinking through.
2. **A bundled breach corpus** (top 10⁵ from a public list, as a filter or hashed set) — no network call, but a real resource and a maintenance story.

Until one is chosen, the gap is recorded here and in `CURRENT_PHASE.md` rather than papered over.

A related fix came out of writing the test: `PasswordPolicyValidator` was package-private, which works under Spring (its `ConstraintValidatorFactory` instantiates reflectively) but fails with `HV000064` under a plain Bean Validation `Validator`, because the spec requires a public no-arg constructor. The class is now `public`. The constraint is used from another package, so this was latent fragility regardless of the test.

## Consequences

**Positive:**
- The wrong-tenant-login requirement is enforced by the tenancy mechanism already proven in Phase 1.1, not by security-specific code that could be bypassed by a future query written a different way.
- `SecurityConfig` stays small and close to Spring defaults: an encoder, a role hierarchy, a filter chain. Less surface to get wrong, less to re-derive when Spring Security 8 lands.
- Stock `DaoAuthenticationProvider` means status checks, credential erasure, and event publication all behave exactly as documented upstream.

**Negative:**
- Lockout does not match PRD §19.1 until Phase 1.11. Two IPs attacking one account share a counter; one IP attacking many accounts is bounded only by the rate limiter.
- `AbsoluteSessionTimeoutFilter` is bespoke code where a framework setting would be preferable.
- Correct behaviour depends on `TenantResolutionFilter` running before the security chain. That ordering is currently expressed only as `Ordered.HIGHEST_PRECEDENCE` on the registration bean; if someone registers another filter at the same precedence the guarantee weakens. Phase 1.2 adds the wrong-tenant integration test, which fails loudly if the ordering ever breaks.

## Alternatives considered

**1. Custom `AuthenticationProvider` scoping the lookup by tenant, as the PRD and Implementation Plan literally specify.** Rejected: it would hand-write a `tenant_id` predicate that ADR 0004 already applies automatically, duplicating the mechanism and creating a second place for tenancy to be wrong. CLAUDE.md §11 lists manual `tenant_id` in a query as an anti-pattern. The spec named an implementation; this decision keeps the requirement and drops the redundant machinery.

**2. Composite `email + tenantId` username passed to `UserDetailsService`.** Rejected: it encodes tenancy into the credential and would surface in the login form or in Spring Security internals. The ThreadLocal already carries it.

**3. Hand-rolled Caffeine rate limiter instead of Bucket4j.** Considered because Caffeine is already a dependency and a fixed-window limiter is roughly forty lines. Rejected: PRD §19.7 names Bucket4j, `bucket4j_jdk17-core` was verified to have **zero transitive dependencies**, and a token-bucket implementation handles burst behaviour more gracefully than a fixed window at no extra cost.

**4. `spring-session-jdbc` from the start.** Rejected as premature for a single instance (CLAUDE.md §11: no optimising without a benchmark). PRD §19.1 already documents the swap, and Decision C keeps application code independent of the store.

## References

- PRD §6.1 (FR-1.1 … FR-1.8), §19.1 (authentication), §19.2 (authorization), §19.7 (rate limiting), §20.7 (suspended tenant), §21 (schemas), §26 (permissions matrix)
- CLAUDE.md §5 (multi-tenancy contract), §6 A01/A02/A03/A05/A07, §12 (ask-first list)
- ADR 0004 (`@TenantId`, `TenantIdentifierResolver`, `TenantSessionVariableListener`)
- ADR 0005 (`user_role` synthetic PK; system-scoped infrastructure tables; the `runAsSystem` correction)
- Implementation Plan §1.2
