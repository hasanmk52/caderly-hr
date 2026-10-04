# ADR 0020 — Tenant logo: Super Admin upload, served same-origin

**Status:** Accepted
**Date:** 2026-09-30
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0012 (file storage), ADR 0016 (email branding), PRD FR-2.5 / FR-12.2, CLAUDE.md §6 A03/A10

---

## Context

`tenant.logo_url` was a free-text column set only at tenant creation, with no edit path, and the web
UI never rendered it. The CSP (`img-src 'self' data:`) also blocks any external image, so a URL-based
logo could never have shown in the app.

## Decision

1. **Upload, not URL.** The Super Admin uploads the logo; bytes go through `FileStorage`
   (`<tenantId>/logo-<uuid>`), with `logo_content_type` and `logo_version` on `tenant`. External URLs
   are rejected by our own CSP, can rot or be swapped, disclose tenant page views to a third party, and
   would need an SSRF allowlist decision (A10).
2. **PNG and JPEG only, 512 KB max**, validated by extension, size and Tika magic bytes. SVG is
   excluded because it can carry script and this image is served from the app's own origin.
3. **Public `GET /tenant-logo`.** The login page needs it before a session exists, so it is
   `permitAll` (one `SecurityConfig` line) and resolved by subdomain like any tenant page, so a host
   can only read its own tenant's logo. `Cache-Control: public, max-age=1y, immutable` is safe because
   templates request `/tenant-logo?v=<logo_version>`; a new upload changes the URL.
4. **Management** lives on the Super Admin tenant list (upload, replace, remove), audited as a
   `Tenant` UPDATE. The create form no longer takes a logo; the logo is added afterwards.
5. **Legacy `logo_url`** is left in place, unexposed in the form, and only feeds the email layout.

## Consequences

- Deviates from PRD FR-12.2's "logo URL" wording. The additive migration is `V202609301000`.
- Email headers show the uploaded logo. `TenantFacade.currentBranding()` builds the absolute
  `<scheme>://<slug>.<base-domain>[:port]/tenant-logo?v=...` address from config
  (`caderly.public-scheme`, `caderly.public-port`), not from the request, because mail is also
  enqueued on scheduler threads. The legacy `logo_url` is only a fallback. Mail clients fetch the
  image from outside the app: fine in production, but a `*.localhost` address only loads in
  Mailpit through the developer's own browser, and some clients hide remote images until the
  reader allows them (the `alt` text, the tenant name, shows meanwhile).
- Replacing a logo deletes the previous object after the row points at the new one; a crash in
  between leaves an orphaned file, never a broken logo.
