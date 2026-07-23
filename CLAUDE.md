# CLAUDE.md — Helyx Codebase Brief

You are working on **Helyx**, a multi-tenant HRIS + Leave Management SaaS built by a solo engineer. This file is your standing brief. Follow it every session. When in doubt, ask before deviating.

---

## 1. Product one-liner

Multi-tenant SaaS replacing TalentHR's free plan for MHZ Software (customer #1) and 2-5 friendly pilot companies. Region-agnostic: every tenant configures its own leave types, weekend days, holiday calendar, and org units.

---

## 2. Where to find things

- **Product spec:** `docs/Helyx_PRD.md` — 29 sections. Cite the section number when quoting.
- **Delivery plan:** `docs/Helyx_Implementation_Plan.md` — phased sub-tasks with DoD.
- **UI guidelines:** `docs/UI_Guidelines.md` — layout, color, typography, component conventions, htmx/Alpine rules. Consult before writing any Thymeleaf template. Any deviation requires updating this document first.
- **Current sub-phase:** `docs/CURRENT_PHASE.md` — read this at the start of every session.
- **Architecture Decision Records:** `docs/adr/NNNN-<slug>.md` — one per significant choice. Add an ADR whenever you make a non-obvious design decision.

**Rule:** At the start of every session, `Read` `docs/CURRENT_PHASE.md` and the specific PRD + Implementation Plan sections it references. Do not skim. Do not assume — read.

---

## 3. Locked stack (do not change without an ADR)

- **Java 25** (LTS), configured in `pom.xml` (`<java.version>25</java.version>`)
- **Spring Boot 4.1.x** on Spring Framework 7
- **Maven** with the Maven Wrapper (`./mvnw`)
- **PostgreSQL 17**
- **Flyway** (forward-only migrations)
- **Hibernate 6 + Spring Data JPA**
- **Spring Security 6** (session cookies, CSRF on, BCrypt cost 12, TOTP MFA planned)
- **Thymeleaf + htmx + Alpine.js + Bootstrap 5** — server-rendered, no SPA
- **Caffeine** (in-JVM cache, no Redis at current scale)
- **Testcontainers Postgres** + JUnit 5 + AssertJ + Mockito
- **ArchUnit** for architecture tests
- **Playwright-Java** for E2E
- **springdoc-openapi** for API docs
- **JaCoCo** for coverage
- **Docker + docker-compose** for deployment of the app + reverse proxy only; **PostgreSQL is external** (host-native install locally and in MHZ prod, managed service in cloud tenants). Distroless JRE 25 base image.
- **Testcontainers** in tests uses its own ephemeral Postgres via Docker — unrelated to dev/prod Postgres.

**Never suggest** React, Redis, Kafka, MongoDB, microservices, JWT-in-localStorage, or any dependency the PRD did not authorize. Push back and cite §20 or §7 if pressed.

---

## 4. Package structure (mirror this exactly)

```
com.helyx.helyxhr
├── identity        # AppUser, roles, tokens, MFA
├── tenant          # Tenant, TenantContext, TenantResolutionFilter
├── org             # Division, Department
├── people          # Employee + sub-entities
├── timeoff         # LeaveType, LeaveBalance, LeaveRequest, PublicHoliday
├── documents       # CompanyFile, EmployeeDocument
├── notifications   # EmailOutbox, EmailTemplate
├── audit           # AuditEntry, LoginAudit, AuditListener
├── reports         # Report services
├── storage         # FileStorage interface + Local + S3 impls
├── security        # SecurityConfig, JwtService, TotpService
├── web             # Thymeleaf controllers (server-rendered)
├── api             # REST controllers under /api/v1
├── superadmin      # Cross-tenant console (separate security realm)
└── common          # BaseEntity, TenantAwareEntity, exceptions, utils
```

**Rules:**
- No cross-package direct DB access. Cross-module reads go through a `<Module>Facade` interface exposed by the owning module.
- No package cycles. Enforced by ArchUnit.

---

## 5. Multi-tenancy contract (non-negotiable)

Every violation is a critical bug. Assume nothing.

1. Every tenant-scoped entity **must** extend `com.helyx.helyxhr.common.TenantAwareEntity` (which has `tenant_id UUID NOT NULL`).
2. Every tenant-scoped table **must** have Postgres RLS enabled with policy `USING (tenant_id::text = current_setting('app.tenant_id', true))`.
3. `TenantContext` is a `ThreadLocal<UUID>` populated by `TenantResolutionFilter` from the subdomain and cleared in `finally`.
4. Every service method activates the Hibernate `tenantFilter` via AOP interceptor — no manual `WHERE tenant_id = ?` in JPQL.
5. Every write auto-sets `tenant_id` via `@PrePersist` on `TenantAssignmentListener`.
6. Any code that needs to bypass tenancy (Super Admin, system jobs) must do so via `TenantContext.runAsSystem(() -> ...)` and audit the action.
7. **Every new entity must have an ArchUnit test proving it extends `TenantAwareEntity`.** Add it in the same PR.
8. **Every service method must have an integration test proving cross-tenant reads return empty.** Two-tenant fixture in `TenantIsolationTestBase`.

If Claude is about to write a query, controller, or repository method: stop and confirm all seven of the above are enforced by construction, not by review.

---

## 6. Security contract (OWASP-aligned, non-negotiable)

Maps to OWASP Top 10 (2021 edition). Every rule is enforced by code + test, not documentation.

### A01 Broken Access Control
- Every controller method annotated with `@PreAuthorize("hasRole('...')")` or `@PreAuthorize("hasAnyRole(...)")`. No unannotated methods allowed — `SecurityConfig` denies by default.
- Row-level access checks on top of tenant filter: employee can only edit own profile; manager can only see reports; owner check enforced in service, not controller.
- Test: for every endpoint, one test per role asserting 200 or 403 as expected.

### A02 Cryptographic Failures
- BCrypt cost 12 for passwords; never MD5, SHA-1, or plain SHA-256 for passwords.
- Reset/invite tokens: 32-byte `SecureRandom`, stored **hashed** (SHA-256) in DB.
- Column encryption via `CryptoConverter` for: `government_id.id_number`, `bank_detail.*`, optionally `employee.base_compensation`. Algorithm: AES-256-GCM. Key from env var; never hardcoded.
- TLS 1.2+ only; HSTS with 1-year max-age + preload.

### A03 Injection
- **Zero string concatenation in SQL/JPQL.** Only parameterized queries or JPA criteria. Grep test: fail CI if `entityManager.createQuery(".*" + ...` appears.
- Input validated with Bean Validation (`@Valid` + `jakarta.validation` annotations) on every DTO.
- File upload: MIME + magic-byte check (Apache Tika) + extension whitelist + size limit. No exec-able types.

### A04 Insecure Design
- Threat-model any new state-changing feature in the ADR before implementing.
- No self-approval (employee cannot approve own leave, even as Manager/Admin — PRD BR-6).
- Rate-limit login (Bucket4j, 10/min/IP), password-reset request (3/hour/email), general API (300/min/session).

### A05 Security Misconfiguration
- CSRF enabled on all state-changing forms (Spring Security default).
- Security headers via `SecurityHeadersFilter`: CSP, X-Frame-Options: DENY, X-Content-Type-Options: nosniff, Referrer-Policy: strict-origin-when-cross-origin, Permissions-Policy minimal.
- Actuator endpoints exposed only over management port + auth; `/actuator/env` and `/actuator/heapdump` disabled in prod profile.
- No default passwords in `application.yml` — everything from env vars.
- Directory listing off. Server banner hidden.

### A06 Vulnerable and Outdated Components
- Dependabot enabled on GitHub.
- OWASP Dependency-Check Maven plugin, runs in CI on PR.
- Trivy scan on built Docker image in CI. Block merge on HIGH/CRITICAL.

### A07 Identification and Authentication Failures
- Session cookies: `HttpOnly`, `Secure`, `SameSite=Lax`, short-lived access + refresh pattern.
- Account lockout after 5 failures in 15 min (per email + IP).
- MFA (TOTP) supported per-user, enforceable per-tenant for Admin role.
- Session revocation on password change and role change.

### A08 Software and Data Integrity Failures
- Only trusted Maven repos (Maven Central + Spring Milestone if needed).
- Maven Wrapper checksum verified.
- Docker base image pinned by digest, not just tag.
- No `eval` / dynamic classloading of user input.

### A09 Security Logging and Monitoring Failures
- Every write goes through `AuditListener` (see §7 of PRD).
- Every login attempt (success + failure) goes to `login_audit`.
- Structured JSON logs to stdout with request ID + tenant ID + actor ID in MDC.
- No secrets, tokens, or PII in logs. Verify with a grep test in CI over log format.

### A10 Server-Side Request Forgery
- No feature currently makes outbound HTTP based on user input. When added (webhooks in Phase 2), URL allowlist per tenant + block private IP ranges (10/8, 172.16/12, 192.168/16, 169.254/16, ::1, fc00::/7).

---

## 7. Coding standards

- **Java 25 features to prefer:** records for DTOs and value objects, pattern matching in `switch`, `sealed` interfaces for state machines (e.g. `LeaveRequestStatus`), `var` for obvious locals only.
- **Style:** Google Java Format via Spotless. Enforced in CI.
- **Static analysis:** Checkstyle + SpotBugs + PMD + ErrorProne. CI blocks merge on new violations.
- **Null-safety:** use `Optional<T>` for return types that may be absent; never for parameters. Non-null by default: annotate the package with `@NullMarked` (JSpecify).
- **Exceptions:** custom `HelyxException` hierarchy with `errorCode`; controller-level `@ExceptionHandler` maps to RFC 7807 Problem Details.
- **Transactions:** `@Transactional` on service methods, never on repositories or controllers. Read-only by default; annotate write methods `@Transactional`.
- **Logging:** SLF4J with `@Slf4j` from Lombok (avoid Lombok elsewhere — records + records builder cover most cases). Log level: `info` for state changes, `warn` for expected failures, `error` for unexpected; no `println`.
- **No Lombok `@Data`** — records for immutable, plain classes for entities. Only `@Slf4j` and `@Builder` (sparingly) are allowed.
- **DTOs are records**, entities are classes with private setters. Never expose entities across the web layer.
- **Fluent builders** for test fixtures via `EmployeeFixture.aRandomEmployee()`.
- **Comment "why", not "what".** The code shows what.
- **Document complex logic with concise "why" comments.** For tricky math (leave duration, working-day calculation), security-sensitive branches, state-machine transitions, non-obvious algorithm choices, workarounds for library or platform quirks, and any edge-case handling that isn't obvious from method + variable names, add a 1–3 line comment above the block explaining the reasoning or the constraint being satisfied. Straightforward code stays uncommented. Never restate what the code obviously does.
- **Dependency versions:** All third-party dependency versions declared in `<properties>` at the top of `pom.xml`, never inline in `<dependency>` blocks. Convention: property key = `<artifactId>.version` (e.g., `bootstrap.version`, `bucket4j.version`). This gives one place to answer "what version of X are we on?" and one line to bump. Do not add a new dependency without also adding its version property.

---

## 8. Testing rules (block merge on failure)

- **Unit:** JUnit 5 + AssertJ + Mockito. Pure logic. Target ≥70% line coverage per module (JaCoCo).
- **Integration:** `@SpringBootTest` + Testcontainers Postgres. Every service happy path + at least one sad path. Every controller: one 200 test + one 403 test per protected endpoint.
- **Architecture:** ArchUnit tests in `com.helyx.helyxhr.architecture` package. Enforce:
  - Every `@Entity` in a tenant package extends `TenantAwareEntity`.
  - Every controller method has `@PreAuthorize`.
  - No cyclic package dependencies.
  - No direct JDBC or `EntityManager.createNativeQuery` outside repositories.
- **Tenant isolation:** Every write test uses `TenantIsolationTestBase` which seeds tenants A and B, and asserts the write only touched the current tenant.
- **E2E:** Playwright-Java, one happy path per user-facing module.
- **Security tests:** OWASP ZAP baseline in nightly CI (weekly at minimum).
- **Test naming:** `methodUnderTest_condition_expectedResult` — e.g. `book_whenBalanceInsufficient_throwsInsufficientBalance`.

**Rule for Claude:** when implementing any feature, write the failing test first, then the code. Especially non-negotiable for: leave duration algorithm, tenant filter, access control decisions, audit writes.

---

## 9. Commit & PR rules

- **Branch naming:** `phase-1.1-multitenancy`, `phase-1.6-leave-approvals`. One branch per sub-phase.
- **Commits:** conventional-commits format (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `chore:`, `sec:`). Present tense, imperative.
- **Commit granularity:** one logical change per commit. `test:` and `feat:` may be separate commits.
- **PR checklist** (mandatory, in template):
  - [ ] All rules in §5 (tenancy) and §6 (security) enforced
  - [ ] Tests: unit + integration + tenant isolation
  - [ ] ArchUnit passes
  - [ ] Migration is additive (or has a documented rollback)
  - [ ] Audit log covers all writes
  - [ ] No secrets in code
  - [ ] PRD section that this implements: §_._

---

## 10. Common task recipes

### Adding a new tenant-scoped entity
1. Extend `TenantAwareEntity`.
2. Add `@Entity` + JPA mapping. No public setters — use methods with intent (`markTerminated(date)`, not `setTerminationDate(date)`).
3. Add ArchUnit test asserting it extends `TenantAwareEntity`.
4. Write Flyway migration `V<timestamp>__add_<entity>.sql`. Add RLS policy.
5. Add repository extending `TenantAwareRepository<T, UUID>`.
6. Add service with `@Transactional` writes.
7. Add DTO (record).
8. Add controller with `@PreAuthorize`.
9. Write integration test + tenant isolation test + one E2E if user-facing.

### Adding a new REST endpoint
1. Confirm role in PRD §26 (permissions matrix).
2. Add controller method with `@PreAuthorize`.
3. Add `@Valid` on request body.
4. Add integration test per role (200 + 403).
5. Ensure OpenAPI annotations produce correct schema.

### Handling a state transition (leave request, employee lifecycle)
1. Model states as `sealed` interface or `enum`.
2. Transition rules in a `<Entity>StateMachine` class, unit-tested exhaustively.
3. Transitions logged to audit.
4. Optimistic locking on the aggregate (`@Version`).

---

## 11. Anti-patterns — do not do these

- Adding `tenant_id` manually in a query. Use the filter.
- Skipping the ArchUnit test "just this once."
- Using `@SuppressWarnings` without a comment explaining why.
- Any `password` field in a log, DTO response, or exception message.
- Storing files in the database. Files go through `FileStorage`.
- Reaching into another module's entities directly. Go through the facade.
- Adding a new dependency without justification in the PR description.
- Writing generic wrappers "in case we need it later." YAGNI.
- Optimizing before there's a benchmark showing a problem.
- Assuming a hedge word means it's OK ("this should be tenant-safe"). Prove it with a test.

---

## 12. When to ask before proceeding

Stop and ask before:
- Any change to `SecurityConfig`, `TenantResolutionFilter`, `AuditListener`, or `TenantAssignmentListener`.
- Adding a new Maven dependency.
- Any schema change that isn't additive.
- Any change to the leave duration algorithm.
- Any deviation from the PRD or Implementation Plan.
- Anything you cannot confidently defend against §5, §6, and §8.

---

## 13. When you start a session

1. `Read` `docs/CURRENT_PHASE.md`.
2. `Read` the specific PRD + Implementation Plan sections it names.
3. Restate the sub-phase's Definition of Done in your own words.
4. Produce a plan in plan mode. Wait for approval.
5. TDD-style: failing tests first, then code.
6. Before saying you're done: run the diff back and prove that §5 and §6 rules hold. Do not claim done if any test is skipped or any TODO remains.

---

*End of CLAUDE.md*
