# ADR 0008 — Employee column encryption (`CryptoConverter`)

**Status:** Accepted
**Date:** 2026-08-12
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0004 (native Hibernate multi-tenancy — establishes the `@TenantId`/RLS
pattern this ADR does not touch), ADR 0007 (htmx admin CRUD transaction pattern, reused by
Employee's admin/self-service endpoints)

---

## Context

Phase 1.4 introduces the first columns that need encryption at rest: `government_id.id_number`,
every field on `bank_detail`, and optionally `employee.base_compensation` (CLAUDE.md §6 A02).
No `CryptoConverter` — or any crypto beyond `SecureToken`'s one-way hashing — exists anywhere
in the codebase yet. Three things needed deciding before writing it: the algorithm/library, how
the converter gets its key without becoming its own hand-wired singleton outside Spring's
container, and the key-management story for an env-var-sourced secret.

## Decision

### A. AES-256-GCM via JDK `javax.crypto` — no new Maven dependency

GCM is an authenticated mode: decryption fails loudly (`AEADBadTagException`, wrapped here as
`IllegalStateException`) if the ciphertext or the prepended IV has been tampered with, rather
than silently returning garbage the way CBC would. The JDK's built-in provider supports it
natively, so this needed no `pom.xml` change and no CLAUDE.md §12 ask-first dependency
conversation.

**Storage format:** a fresh random 12-byte IV (GCM's recommended nonce size) prepended to
ciphertext+tag, stored as one `bytea` column. A fresh IV per encryption is not an optional nicety
— reusing an IV with the same key breaks GCM's confidentiality guarantee outright, so encrypting
the same plaintext twice deliberately produces different bytes. None of these columns are ever
queried by equality, so this has no functional cost.

### B. `CryptoConverter` is a Spring `@Component`, not a plain JPA converter

Confirmed via Hibernate ORM's source (`ManagedBeanRegistry` / `AttributeConverterBean`):
Hibernate resolves every `@Converter` through its `ManagedBeanRegistry`, which delegates to
whatever `BeanContainer` is configured — and Spring Boot auto-configures a Spring-backed one for
Hibernate. A `@Component @Converter` class therefore gets ordinary constructor injection, not the
no-arg instantiation a converter gets under a bare JPA provider. This is what lets the encryption
key arrive via `@Value("${caderly.people.encryption-key}")` instead of a static field or a
hand-rolled bean lookup — one converter instance, managed by Spring like everything else.

### C. Key sourcing: env var, no default in prod, fixed defaults in dev/test

`caderly.people.encryption-key` (base64, 32 raw bytes) follows the exact pattern
`CADERLY_EMAIL_FROM` already established: `application.yml` has **no default** —
`${CADERLY_EMPLOYEE_ENCRYPTION_KEY}` — so a missing key fails startup rather than silently running
with a guessable one (CLAUDE.md §6 A05). `application-dev.yml` and `application-test.yml` each
pin a fixed, checked-in key — genuinely fine for those profiles since dev/test databases hold no
real government IDs or bank details, and Testcontainers-backed integration tests need a working
key without every contributor provisioning a real secret locally.

### D. No key rotation in v1 — recorded as a deferred gap, not an oversight

Rotating the key would require re-encrypting every existing row (there is no key-id column
alongside the ciphertext to support multiple live keys at once). Out of scope for a phase whose
DoD is "employee CRUD works end to end" — revisit if/when a real key-compromise or
compliance-driven rotation requirement shows up. If it does, the fix is a `key_version` column
per encrypted table plus a converter that picks the decryption key by version and always
encrypts with the newest — not a change to the storage format decided in Section A.

## Consequences

**Positive:**
- Zero new dependencies; the whole implementation is ~70 lines using JDK primitives already on
  every classpath.
- Tamper-evident by construction (GCM's authentication tag), not just confidentiality.
- The Spring-bean-converter pattern established here is reusable as-is for any future encrypted
  column — no per-column boilerplate beyond `@Convert(converter = CryptoConverter.class)`.

**Negative:**
- No key rotation. A compromised key means re-encrypting the affected columns is a one-off
  manual migration, not a supported operation, until Decision D's deferred design lands.
- A single key encrypts every tenant's sensitive data. Per-tenant keys were considered and
  rejected for v1: they would multiply key-management surface (N secrets instead of one) for a
  threat model — one shared application secret compromised — that a per-tenant key does not
  actually change, since the application itself needs access to all of them to serve any tenant.

## Alternatives considered

**1. A dedicated crypto library (Google Tink, Spring Security Crypto's `AesBytesEncryptor`).**
Rejected for v1: `javax.crypto` GCM is well-understood, needs no new dependency, and this
codebase already reads raw `SecureRandom`/`MessageDigest` directly in `SecureToken`. Revisit if
key rotation (Decision D) or envelope encryption against a KMS becomes a real requirement — that
is exactly the point where a library like Tink starts paying for itself.

**2. Deterministic encryption (fixed IV, or a mode that permits equality search on ciphertext).**
Rejected: none of the encrypted columns need to be searched or joined on, and deterministic
encryption leaks equality (two employees with the same bank name would have identical
ciphertext), which is a real information leak GCM's random-IV mode avoids for free.

**3. Encrypt at the application/service layer instead of via a JPA `AttributeConverter`.**
Rejected: a converter makes encryption transparent to every current and future caller of the
entity's accessor methods — there is no second code path where someone reads the raw encrypted
bytes by forgetting to decrypt. `@Convert` on the field is the only thing a new encrypted column
needs.

## References

- PRD §6.3 (FR-3.7 government IDs, FR-3.8 bank details), §21 (schema)
- CLAUDE.md §6 A02 (cryptographic failures), §12 (ask-first list — new dependency; not
  triggered here since `javax.crypto` ships with the JDK)
- ADR 0004 (native Hibernate multi-tenancy — the pattern this converter sits alongside, not
  interacts with; RLS/`@TenantId` are unaffected by column-level encryption)
- Implementation Plan §1.4
