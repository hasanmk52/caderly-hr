# ADR 0013 — i18n message externalization: key convention, fixed locale, and locale scope

**Status:** Accepted
**Date:** 2026-08-29
**Deciders:** Hasan (solo dev)
**Relates to:** CLAUDE.md §7 (coding standards), PRD §7 (NFR "Internationalization"), Implementation Plan Phase 2 (Arabic + RTL, language toggle)

---

## Context

PRD §7's Non-Functional Requirements table has an "Internationalization" row: *"All UI strings externalized (messages.properties). English only for MVP."* No i18n scaffolding existed anywhere in the codebase — no `messages.properties`, no `MessageSource` bean, no `LocaleResolver` — and all 24 Thymeleaf templates, ~35 exception throw sites, Bean Validation constraints, and the two transactional-email builder classes carried hardcoded English text. Actual multi-language UI (Arabic + RTL, a Settings language toggle) is scheduled for Phase 2 in the Implementation Plan, well after this MVP work. This ADR covers the infrastructure built now — string externalization and a fixed English locale — not the future multi-language switch itself.

## Decision A: `spring.web.locale-resolver: FIXED`, not `ACCEPT_HEADER` or a custom resolver

Spring Boot 4.1's `WebProperties` (`spring.web` namespace) exposes `locale` and `locale-resolver` (`FIXED`/`ACCEPT_HEADER`, default `ACCEPT_HEADER`) with no custom bean required. `FIXED` + `locale: en` means every request resolves to English regardless of the client's `Accept-Language` header — correct for an English-only MVP, and it means `LocaleContextHolder.getLocale()` is safe to call anywhere in a request-handling path without special-casing.

**Rejected: `ACCEPT_HEADER`, session-based, or cookie-based locale switching now.** No second locale bundle exists yet — building a switching mechanism ahead of having anything to switch to is exactly the "generic wrapper in case we need it later" CLAUDE.md §11 warns against. When Phase 2 ships `messages_ar.properties` and a Settings language toggle, `spring.web.locale-resolver` flips to a session/cookie strategy — a one-line config change, since every caller already reads locale through `LocaleContextHolder`, not a hardcoded `Locale.ENGLISH`, inside request-handling code.

## Decision B: message-key naming convention

Dot-notation, lowercase, hyphen-separated multi-word segments (`.properties`-file convention):

| Origin | Scheme | Example |
|---|---|---|
| Page-specific template text | `<module>.<page>.<element>` | `people.profile.tab.employment` |
| Table headers reused only on one page | `<module>.<page>.table.<column>` | `people.list.table.department` |
| Shared/reused strings (buttons, generic table headers, ARIA labels) | `common.<element>` | `common.action.cancel`, `common.table.actions` |
| Empty states | `<module>.<page>.empty` | `files.list.empty` |
| Exception detail | `error.<errorCode, lowercased, underscores→hyphens>` | `DEPARTMENT_NOT_FOUND` → `error.department-not-found` |
| Bean Validation | `validation.<form>.<field>.<constraint>` | `validation.book-leave-form.leave-type-id.required` |
| Email subject/body fragment | `email.<flow>.subject` / `email.<flow>.body.<fragment>` | `email.leave-requested.subject` |

**Exception messages reuse the existing `errorCode` as the key, transformed, rather than a hand-maintained separate key.** Every `CaderlyException` subclass already carries a stable `errorCode` (e.g. `"DEPARTMENT_NOT_FOUND"`) reviewed and used as the RFC 7807 `type` URI slug. Deriving the message key from it (`"error." + errorCode.toLowerCase().replace('_','-')`) means one identifier per failure mode instead of two that could drift out of sync, and `GlobalExceptionHandler` can compute it programmatically rather than needing a lookup table.

## Decision C: `GlobalExceptionHandler` resolves via `MessageSource`, `CaderlyException` itself is unchanged

`CaderlyException`/`ConflictException`/`NotFoundException`/`ValidationException` keep their current shape — `errorCode` + a single `message` String, no args array, no `{0}`-style placeholder support. `GlobalExceptionHandler` gains a constructor-injected `MessageSource` and resolves:

```java
messageSource.getMessage("error." + slug(errorCode), null, exception.getMessage(), LocaleContextHolder.getLocale())
```

The 3-arg overload's fallback default is the exception's own existing message string, so none of the ~35 existing throw sites across services need editing — only `messages.properties` needs an `error.*` entry per `errorCode` (a mechanical population pass), and a throw site that's missing its entry still renders its original English text instead of breaking.

**Rejected: adding an `Object[] args` field to `CaderlyException` for parameterized messages now.** No current throw site needs locale-sensitive word-order placeholder substitution (parameterized messages today are pre-formatted strings built at the throw site with `.formatted()`/concatenation). Adding args support ahead of a real need, touching all ~35 constructors, is speculative per CLAUDE.md §11. Revisit if/when Phase 2's Arabic bundle needs `{0}`-style reordering.

## Decision D: emails use explicit `Locale.ENGLISH`, not `LocaleContextHolder`

`timeoff.TimeoffEmails` and `identity.IdentityEmails` stay stateless final classes with static methods (not converted to `@Component`s) — `MessageSource` and `Locale` are passed as explicit parameters from the calling `@Service` (`LeaveRequestService`, `InviteService`, `PasswordResetService`). The locale passed is `Locale.ENGLISH`, a hardcoded constant, not `LocaleContextHolder.getLocale()`.

Email content is built at outbox *enqueue* time, today always inside the HTTP request thread that triggered it — so `LocaleContextHolder` would technically resolve correctly right now. But `caderly.email.dispatcher.*` (CLAUDE.md §6a's durable-outbox pattern) makes email fundamentally a background-processing concern, and `EmployeeTerminationJob`/`AnnualGrantJob`-style scheduled paths already exist in the codebase with no request context at all. Relying on request-thread locale for something conceptually background-shaped is fragile — a future email-triggering code path added inside a scheduled job would silently get the JVM default locale instead of failing loudly. Hardcoding `Locale.ENGLISH` costs nothing while only English exists and removes this failure mode before it can occur.

## Consequences

**Positive:**
- Adding `messages_fr.properties`/`messages_pt.properties` later is a content-only change — `Decision A`'s config-only resolver swap and `Decision B`'s convention mean no template, exception, or validation call site needs to change shape.
- `Decision C` means populating `messages.properties` for exceptions is a mechanical audit, not a 35-call-site refactor.
- `Decision B`'s errorCode-as-key reuse keeps exactly one identifier per failure mode.

**Negative / open (known, accepted limitation):**
- `CaderlyException` has no placeholder/args mechanism — a future locale needing different word order for a parameterized message (e.g. "You have {0} days remaining") will need that added then, not now (Decision C).
- The exact scope of "every UI string" is bounded by what a manual final-sweep review catches in the 24 templates; a fully automated "no hardcoded template string" CI check was judged impractical (too many legitimate `${...}` and `utext` exceptions) and isn't built.
- `tenant.locale` (already in the DB schema, PRD §21) remains unused by this work — it's Phase 2's mechanism to actually resolve per-tenant/per-user locale once more than English exists.

## Alternatives considered

**1. `ACCEPT_HEADER` resolver now, even with only English translated.** Rejected: gives the appearance of locale-awareness with no second bundle behind it — a browser set to French would get English silently rather than predictably, no functional benefit over `FIXED` today.

**2. Extend `CaderlyException` with an `Object[] args` field in this pass, since it touches ~35 call sites anyway for the properties audit.** Rejected: the properties audit only needs to *read* each throw site's message text, not edit its constructor call — adding args support is a separate, larger change with no current consumer.

**3. Make `TimeoffEmails`/`IdentityEmails` `@Component`s so `MessageSource` can be constructor-injected instead of passed as a parameter.** Rejected: these classes are pure stateless string-building functions today; adding Spring bean lifecycle purely to get DI when a plain parameter achieves the same result is unjustified complexity.

## References

- PRD §7 (NFR table, "Internationalization" row), §21 (`tenant.locale` column), §3.2/§24.10 (Phase-2-scoped language settings)
- Implementation Plan, Phase 2 — Depth & Polish ("i18n message bundles for AR; RTL CSS variant", "Language toggle in Settings; direction detection")
- CLAUDE.md §7 (coding standards), §11 (anti-patterns: generic wrappers, speculative abstraction), §6a (durable-outbox pattern — the reasoning behind Decision D)
- `com.caderly.caderlyhr.web.GlobalExceptionHandler`, `com.caderly.caderlyhr.common.CaderlyException`
- Spring Boot 4.1.0 `org.springframework.boot.autoconfigure.web.WebProperties` (`spring.web.locale` / `spring.web.locale-resolver`), confirmed against the pinned dependency's sources jar, not assumed from memory
