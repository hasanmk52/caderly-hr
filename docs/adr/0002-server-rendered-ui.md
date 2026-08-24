# 0002 — Server-rendered UI: Thymeleaf + htmx + Alpine.js + Bootstrap 5

## Status

Accepted

## Context

Caderly is built and maintained by a solo engineer replacing TalentHR's free plan for a small set of tenants. A separate SPA frontend (React/Vue) would double the surface area to build, test, and secure (a JSON API, client-side auth/session handling, a build pipeline) for a product whose UI is largely CRUD forms, tables, and simple dashboards — see PRD §1.

## Decision

Server-rendered pages via Thymeleaf, progressively enhanced with htmx (partial page swaps for things like row updates and tab content) and Alpine.js (small client-side interactions — offcanvas forms, toggles) on top of Bootstrap 5 for layout and components. No SPA framework, no client-side router, no separate JSON-driven frontend build.

## Consequences

- One codebase, one deployment artifact, one auth model (session cookies, CSRF via Spring Security defaults) — no JWT-in-localStorage, no CORS surface to secure.
- REST endpoints under `/api/v1` still exist for integrations/API consumers, but the product UI does not depend on them.
- Interaction patterns are constrained to what htmx/Alpine do well; a future UI that needs heavy client-side state would require a fresh decision, not a bolt-on.
