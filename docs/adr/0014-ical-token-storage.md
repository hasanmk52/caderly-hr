# ADR 0014 — iCal feed token: raw storage on `app_user`, not hashed, not a separate table

**Status:** Accepted
**Date:** 2026-08-30
**Deciders:** Hasan (solo dev)
**Relates to:** CLAUDE.md §4 (package structure), §6 A02 (token generation/storage rule), §21 (schema)

---

## Context

Phase 1.8 (`docs/CURRENT_PHASE.md`) adds a per-user, tokenized iCal feed URL (PRD FR-6.5, §9.4 US-CAL.3, AC-CALENDAR.1). CLAUDE.md §6 A02 says: "Reset/invite tokens: 32-byte `SecureRandom`, stored **hashed** (SHA-256) in DB." `CURRENT_PHASE.md` explicitly flagged this as a decision to check rather than copy blindly: "the iCal use case (a long-lived, repeatedly-fetched URL) differs from a one-time reset token, so don't copy that pattern without checking whether it actually fits."

`identity.PasswordResetToken` is the existing precedent for §6 A02: the raw token is generated once, put in an email, and never needed again — only its SHA-256 hash is ever persisted, and lookup is by hash equality (`PasswordResetTokenRepository#findByTokenHash`). This works because the token is shown to its owner exactly once, at the moment it's minted.

The iCal token doesn't fit that shape. Settings → Calendar integration must **redisplay the same URL** every time the user opens that page, so they can re-copy it if they lost it or need to paste it into a different calendar app. A SHA-256 hash is one-way — there is no way to reconstruct the raw value from it to show the user again. The password-reset precedent (and the invite-token precedent it shares) doesn't have this requirement, because both are single-use bootstrap secrets, not a standing credential the owner returns to.

## Decision

Store the raw, `SecureToken`-generated 32-byte value directly in a nullable `ical_token` column on `app_user`, unique-indexed:

```sql
ALTER TABLE app_user ADD COLUMN ical_token varchar(64);
CREATE UNIQUE INDEX app_user_ical_token_key ON app_user (ical_token) WHERE ical_token IS NOT NULL;
```

- **Column on `app_user`, not a new table.** The relationship is strictly 1:1, non-expiring, and needs no history — the same shape as the existing `mfa_secret` column on `AppUser` (also an unhashed secret-at-rest, for the identical reason: TOTP needs the raw secret back to verify a code, not just a hash of it). Introducing a `user_ical_token` table (the PRD's other option) would add a join for every feed request and a lifecycle to manage for no benefit, since there is never more than one live token per user.
- **Not hashed.** The entropy of a 32-byte `SecureRandom` value is what makes it unguessable; hashing adds nothing here beyond making the value unrecoverable for redisplay, which is a cost with no corresponding benefit for this credential shape. `CalendarTokenService#getOrCreateToken` and `#regenerateToken` (the only writers) treat it as a long-lived bearer credential — closer to an API key than a password-reset link.
- **Rotation is a plain overwrite** (`AppUser#issueIcalToken`), not a delete-and-reinsert. The old value simply stops resolving on the next `AppUserRepository#findByIcalToken` lookup — no separate revocation step needed.
- **Lazily generated**, get-or-create on first visit to Settings → Calendar integration (`CalendarTokenService#getOrCreateToken`) — not provisioned at invite time. No secret exists for a user who never uses the feature.

## Consequences

**Positive:**
- Settings → Calendar integration can always show the current URL on demand, matching how every comparable product (Google Calendar's own private address, GitHub's private feed URLs) treats this class of credential.
- No join needed for the one hot, unauthenticated, repeatedly-polled read path (`CalendarFeedController` → `AppUserRepository#findByIcalToken`).
- Rotation ("Regenerate link" on the Settings page) is a single-column update with immediate effect.

**Negative / accepted trade-off:**
- If the `app_user` table is ever read from a source that doesn't respect application-level access control (a raw DB dump, a backup), the iCal token is directly usable, unlike a hashed reset token. This is judged acceptable because the same is already true of `mfa_secret` on the same table, and the token itself grants read-only access to one person's own approved leave — not credentials, not write access, not other tenants' data (proven by the RLS/tenant-isolation tests in `IdentityTenantIsolationTest`).
- No expiry. A leaked URL stays valid until the owner notices and clicks "Regenerate link." This matches the product shape (a calendar subscription URL, not a one-time link) and is the same trade-off Google Calendar's own private address makes.

## Alternatives considered

**1. Hash it like `PasswordResetToken`, and require re-generation (not re-display) every time the user wants the URL.** Rejected: this would make "copy my calendar link" a destructive action that breaks any calendar app already subscribed, every single time the user just wants to double check the URL — a materially worse UX for no security gain, since the raw value already has to leave the server once per subscription anyway.

**2. A separate `user_ical_token` table (the PRD's literal first option).** Rejected: no history, expiry, or multiplicity requirement exists to justify it; it would only add a join to the feed's hot path. Revisit if the product ever needs multiple simultaneous tokens per user (e.g., one per calendar app) — not required by Phase 1.8's scope.

## References

- CLAUDE.md §6 A02 (token generation/hashing rule this deviates from, with reasoning)
- `identity.PasswordResetToken` / `PasswordResetTokenRepository#findByTokenHash` (the hashed-token precedent this is deliberately not copying)
- `identity.AppUser#mfaSecret` (the existing unhashed-secret-at-rest precedent this follows instead)
- `calendar.CalendarTokenService`, `calendar.CalendarFeedService` (the two consumers)
- `docs/CURRENT_PHASE.md`'s Phase 1.8 brief (explicitly flags this as a decision to check, not assume)
