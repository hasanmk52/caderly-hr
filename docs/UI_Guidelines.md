# Caderly — UI Guidelines

**Version:** 1.0
**Scope:** All server-rendered pages in the Caderly web app (Thymeleaf + htmx + Alpine.js + Bootstrap 5).

The goal is a consistent, calm, professional HR product that feels closer to Linear/Notion polish than to legacy enterprise HR. When in doubt, choose the simpler visual choice — HR software is used by people who don't want to be looking at it.

Grounded in the TalentHR screenshots the PRD was derived from, but Caderly should look sharper: tighter type scale, more consistent spacing, better empty states.

---

## 1. Layout shell

**Fixed top bar (56 px):** logo left · global search center · primary CTA ("Book time off") · user avatar + menu right.

**Left sidebar (240 px, collapsible to 64 px on <lg):** primary navigation with icon + label. Items:
- Home
- People
- My Profile
- Files
- Calendar
- For Action
- Admin (only role=ADMIN)
- Reports (only role=ADMIN)

Active nav item: `--bs-primary-bg-subtle` tint background + `--bs-primary` left border (3 px) + `--bs-primary-text-emphasis` text + 600 weight.
Inactive: default text color, hover raises background to `--bs-tertiary-bg`. Every nav-link carries a transparent 3 px left border at baseline so activating one doesn't shift layout.

**Content area:** `max-width: none` (use full width) with `--bs-body-bg` background, page-scoped `container-fluid` with `py-4 px-4`. First element is always a page header (`<h1>` + optional right-aligned actions).

**Mobile (<md):** sidebar collapses into an offcanvas triggered from a hamburger in the top bar. Content becomes single-column.

---

## 2. Color palette

Two layers: **system colors** (Bootstrap 5 semantic) and **tenant brand color** (per-tenant primary).

### System (defaults)
| Token | Value | Use |
|---|---|---|
| `--bs-primary` | tenant.primary_color, default `#4f46e5` | Primary buttons, links, active nav, focus rings |
| `--bs-primary-rgb` | `79, 70, 229` | rgba() derivatives of primary |
| `--bs-primary-text-emphasis` | `#3730a3` | Text on primary-subtle backgrounds (Admin badge, sidebar active) |
| `--bs-primary-bg-subtle` | `#e0e7ff` | Tinted backgrounds (Admin badge, sidebar active) |
| `--bs-primary-border-subtle` | `#c7d2fe` | Borders on primary-tinted surfaces |
| `--bs-secondary` | `#6C757D` | Secondary buttons |
| `--bs-success` | `#16A34A` | Approved chip, success toast |
| `--bs-warning` | `#F59E0B` | Pending chip, warning toast, orange CTA accent |
| `--bs-danger` | `#DC2626` | Rejected chip, delete buttons, error toast |
| `--bs-info` | `#0EA5E9` | Informational callouts |
| `--bs-body-bg` | `#f8f9fb` | Content area background |
| `--bs-body-color` | `#1f2328` | Body text |
| `--bs-border-color` | `#e5e7eb` | Default hairline borders, table dividers |
| `--bs-tertiary-bg` | `#f3f4f6` | Hover backgrounds (sidebar inactive hover, dropdown-item hover) |

Sidebar is **not** a solid dark color — it's `bg-white` with a `border-end`, per the fragment actually shipped (`fragments/sidebar.html`). Active item: `--bs-primary-bg-subtle` tint + `--bs-primary` 3 px left border + `--bs-primary-text-emphasis` text. Inactive hover: `--bs-tertiary-bg`. (An earlier version of this doc specified a solid dark-navy sidebar with a solid-fill active state; that was never built. This section now documents the light sidebar that exists.)

**Bootstrap 5.3 gotcha:** `-bg-subtle`, `-text-emphasis`, `-border-subtle`, and `-rgb` tokens (and `--bs-focus-ring-color`) are static hex values compiled into Bootstrap's CSS — they are *not* derived from `var(--bs-primary)` at runtime. Changing `--bs-primary` alone leaves the Admin badge, sidebar active state, and focus ring on stock Bootstrap blue. Always override the whole primary family together (see `static/css/theme-overrides.css`).

### Radius scale
| Token | Value |
|---|---|
| `--bs-border-radius-sm` | `0.375rem` — small controls (`btn-sm`, `form-control-sm`) |
| `--bs-border-radius` | `0.5rem` — base (buttons, inputs, cards, badges) |
| `--bs-border-radius-lg` | `0.75rem` — modal/offcanvas content |

### Elevation scale
| Token | Value | Use |
|---|---|---|
| `--bs-box-shadow-sm` | `0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.08)` | Cards (`shadow-sm`) |
| `--bs-box-shadow` | `0 4px 12px rgba(16,24,40,.08), 0 2px 4px rgba(16,24,40,.06)` | Dropdowns, modals |
| `--bs-box-shadow-lg` | `0 12px 32px rgba(16,24,40,.12), 0 4px 8px rgba(16,24,40,.06)` | Offcanvas (targeted override — Bootstrap ships no shadow var for it by default) |

### Tenant primary color
`tenant.primary_color` (hex, per PRD §5) is injected into `--bs-primary` at layout render via a `<style>` tag in `head.html`. **Not yet implemented** (Phase 1.10 owns this, per `docs/CURRENT_PHASE.md`'s carried-forward items). When it is: per the gotcha above, the tenant `<style>` block must set the *whole* primary token family (rgb / text-emphasis / bg-subtle / border-subtle / focus-ring / link colors), computed from the tenant's hex the same way `theme-overrides.css` derives it from `#4f46e5` — not just `--bs-primary` — or a custom tenant color will look inconsistent against the Admin badge and sidebar active state.

**Resolved:** the `tenant.primary_color` column's DB default originally shipped as `#2563EB` (`V202607241000__create_tenant_and_super_admin.sql`) — the color this document specified before this pass, not the `#4f46e5` it specifies now, and MHZ's own row still held it since nothing sets `primary_color` explicitly at seed time. The migration's default and MHZ's row were both updated to `#4f46e5`, and the PRD's schema snippet (Caderly_PRD.md §5) was updated to match. Phase 1.10 can now wire up the `<style>` injection without inheriting a stale default.

### Do not
- Hardcode hex values in templates. Use `var(--bs-primary)` or Bootstrap utility classes.
- Add more accent colors beyond what's in this table without discussion.
- Introduce dark mode in MVP — one theme, done well.

---

## 3. Typography

- **Family:** `"Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`. `SecurityConfig`'s CSP (PRD §19.6) deliberately locks `font-src`/`style-src` to `'self'` — "every asset is served from WebJars on our own origin" — so a CDN webfont (e.g. Bunny Fonts) was ruled out. Inter is instead **self-hosted** via `org.webjars.npm:fontsource__inter` (pom.xml), the same webjar mechanism already used for Bootstrap/Bootstrap Icons/Alpine: the actual woff2 files ship inside the jar with local relative `@font-face` paths, served same-origin through `/webjars/**`, so no CSP change was needed. `head.html` links only the `400.css`/`500.css`/`600.css` weight files — matching this section's "no 700+" rule — not the full weight set the webjar ships. `system-ui` remains as the fallback while the webfont loads or if it fails.
- **Scale:**
  - `h1`: 1.75 rem / 600 weight — page titles
  - `h2`: 1.5 rem / 600 — section headers within a page
  - `h3`: 1.25 rem / 600 — card / widget titles
  - `h4`: 1.0625 rem / 600 — sub-sections
  - `body`: 1 rem / 400 — default paragraph
  - `small`: 0.875 rem / 400 — helper text, table meta, labels
  - `micro`: 0.75 rem / 500 uppercase tracked — badge labels only
- **Line height:** 1.5 for body, 1.25 for headings.
- **Weight:** 400 default, 500 for emphasis, 600 for headings and interactive labels (nav, buttons). Do not use 700+.

---

## 4. Spacing scale

Use **Bootstrap spacing utilities exclusively** — no inline `style="margin:...`.

Prefer: `mb-2` (0.5rem), `mb-3` (1rem), `mb-4` (1.5rem), `mb-5` (3rem). Same scale for `mt-`, `p-`, `gap-`.

- Between form fields: `mb-3`.
- Between form sections: `mb-4`.
- Between page-level blocks: `mb-5`.
- Card body padding: default `p-3`, oversized cards `p-4`.
- Table cell padding: default; do not override without cause.

---

## 5. Iconography

- **Primary icon set:** Bootstrap Icons (`bi-*`), already in classpath via WebJar.
- **Empty-state illustrations:** use unDraw (public domain SVG). Store under `src/main/resources/static/img/empty/` and reference by name. No custom illustration commissioning in MVP.
- **Leave-type icons:** each `LeaveType` has an icon name field (e.g., `bi-palm`, `bi-bandaid`, `bi-calendar-heart`). Rendered wherever the type appears, colored per `LeaveType.color`.
- **Sizing:** icons inline with text = `1em`, standalone icons in cards = `1.25rem`, empty-state illustrations = `240 px` wide.

---

## 6. Component conventions

### Buttons
- **Primary action per screen:** exactly one `btn-primary`. Everything else `btn-outline-secondary` or `btn-link`.
- **Destructive:** `btn-danger` only inside confirmation modals; on list rows use `btn-outline-danger btn-sm`.
- **Cancel is always a button, never a link:** `btn-outline-secondary`, whether it dismisses a modal/offcanvas or navigates away (an `<a class="btn btn-outline-secondary">` for the latter). `btn-link` is for genuinely link-like actions (sortable column headers, "Forgot password?"), not for Cancel.
- **Icon-only:** always add `aria-label` and use `btn` + Bootstrap Icon child.
- **Sizes:** default for main CTAs, `btn-sm` in tables and toolbars, avoid `btn-lg`. Modal and offcanvas action bars (§ Modals, § Slide-over panels) use `btn-sm` for both buttons — these sit inside an already-compact panel, not the main page CTA.

### Links
Both `.btn-link` (sortable column headers, "Forgot password?") and plain in-content `<a>` (table row → detail links, empty-state "How-to guide" links) share one treatment, overridden in `theme-overrides.css`: 500 weight, no underline at rest, underline on hover/focus. Nav links, dropdown items, the navbar brand, and buttons are excluded — they keep their own component styling. Do not hand-roll a different link style; extend the shared selector in `theme-overrides.css` if a new case doesn't fit.

### Cards
- Use `card` for all widgets, list items with heavy content, dashboard tiles.
- Structure: header (title + optional actions on right) → body → optional footer. Header uses `card-title` (`h3`).
- Cards do not stack shadows. Bootstrap default `shadow-sm`. Never `shadow-lg`.

### Tables
- Wrapper `table-responsive` on every table.
- Use `table table-hover align-middle` classes always. Never `table-striped` (looks dated).
- Header cells: `<th scope="col">` in every case (accessibility).
- Empty table: render empty-state block, not an empty `<tbody>`.
- Row actions in a right-aligned column with `text-end`, use `btn-outline-secondary btn-sm` icon buttons.
- Sortable columns: header is a `btn-link` with an up/down chevron icon.

### Forms
- Every input has `<label>` linked by `for`/`id`. No placeholder-as-label.
- Required fields marked with `<span class="text-danger">*</span>` after the label.
- Use `form-floating` for compact forms (single-column), plain `form-label` above input for standard forms.
- Validation: server-side via Bean Validation. Render errors as `invalid-feedback` sibling of the input, with `is-invalid` on the input.
- Success feedback after submit: toast (see §7.4), not banner.
- Form actions bar at bottom: primary on right, "Cancel" as `btn-outline-secondary` on left, both `btn-sm` inside a modal/offcanvas (§6 Buttons). On mobile, stack full-width.
- Disable submit while htmx request is in flight (`hx-indicator` swaps a spinner).

### Modals
- Use for: booking leave, confirm delete, MFA prompt, invite user.
- `modal-lg` for booking (needs date picker), `modal` default otherwise.
- Never nest modals.
- Close on backdrop-click and Esc unless the modal has unsaved changes (guard via Alpine).

### Slide-over panels (Bootstrap offcanvas)
- Use for: editing a resource inline (edit department, edit leave type, edit employee section).
- Right-side, `offcanvas-end`, width ~480 px.
- Always has an explicit close X and a footer with Cancel (`btn-outline-secondary btn-sm`) + Save (`btn-primary btn-sm`).

### Tabs (profile pages)
- Use Bootstrap `nav-tabs` with `nav-link` items and `tab-pane` bodies.
- htmx `hx-get` swaps tab bodies (`hx-target="#tab-content"`) — do not render all tabs on initial page load.
- Active tab reflected in URL query param (`?tab=personal`) for shareability.

### Chips / status badges
Standard pill mapping — never invent new colors:

| Status | Bootstrap class | Icon |
|---|---|---|
| Pending | `badge bg-warning-subtle text-warning-emphasis` | `bi-clock` |
| Approved | `badge bg-success-subtle text-success-emphasis` | `bi-check-circle` |
| Rejected | `badge bg-danger-subtle text-danger-emphasis` | `bi-x-circle` |
| Cancelled | `badge bg-secondary-subtle text-secondary-emphasis` | `bi-slash-circle` |
| Employee role | `badge bg-dark text-white` | — |
| Manager role | `badge bg-info-subtle text-info-emphasis` | — |
| Admin role | `badge bg-primary-subtle text-primary-emphasis` | — |

Existing code in `people/list.html`, `people/profile.html`, `admin/users.html`, `admin/leave-types.html` predates this table and uses Bootstrap's stock solid `badge text-bg-{color}` helpers instead of the subtle/emphasis pair above. `theme-overrides.css` repoints `.badge.text-bg-*` to the matching subtle/emphasis colors via CSS override, so those existing badges already render with the soft-pill look this table specifies — no template edit needed. New code should still prefer writing `bg-*-subtle text-*-emphasis` directly per the table; both forms now look identical.

### Avatars
- Circle with initials fallback (first + last, uppercased) when no photo.
- Default background: hash of employee ID mapped to one of 8 muted colors (deterministic — same person always same color).
- Sizes: `24 px` in tables, `32 px` in nav, `40 px` in cards, `96 px` on profile.

---

## 7. Data patterns

### 7.1 Empty states
Every collection view (People, Files, Documents, Time-off history, Tasks, Approvals, Reports) must have a designed empty state. Never show an empty table or a bare "No results."

Structure:
1. Centered unDraw illustration (240 px wide, opacity 0.85).
2. `h3` explanatory title ("No documents yet").
3. One-line supporting text ("Upload employment docs like contracts, IDs, certifications.").
4. Primary action button ("Upload document").
5. Optional secondary "How-to guide" link.

### 7.2 Loading states
- Full-page load: no skeleton needed (Thymeleaf is fast).
- htmx swap: show `hx-indicator` — a small spinner near the trigger element.
- Long-running actions (e.g., report export): disable the button, swap label to "Preparing…" with a spinner.

### 7.3 Error states
- Field-level: `is-invalid` + `invalid-feedback` per §6 Forms.
- Page-level (server error, network error): render inline `alert alert-danger` at the top of the affected component. Do NOT redirect to a generic error page mid-flow.
- Global 5xx: dedicated `error/5xx.html` with support contact + request ID.
- Global 404: dedicated `error/404.html` with search bar. (The search bar waits on global search; until then the page offers navigation instead of a control that would not work.)
- Global 403: dedicated `error/403.html` — "Access denied", worded so it does not confirm whether the page exists.
- **Every other status must land somewhere too.** Spring Boot resolves `templates/error/<status>.html` and then `templates/error/<series>xx.html`; anything matching neither falls through to Boot's Whitelabel page, which is unbranded and — with devtools active — prints a stack trace. `error/4xx.html` and `error/5xx.html` exist as the catch-alls that make this impossible. Add a status-specific page when the wording matters; never remove the catch-alls.
- Verify error pages by **looking at the rendered page**, not the status code. A correct 403 status with a Whitelabel body is a defect that status-only tests pass.

### 7.4 Success feedback
Toasts (Bootstrap `toast`) in the top-right, auto-dismiss after 4 s. Use `bg-success` for confirmations, `bg-info` for informational. Toasts are the ONLY confirmation mechanism for non-destructive actions — never use alerts or reload the page just to show success.

Destructive actions (delete, terminate): confirm via modal, then show a toast on success.

### 7.5 Confirmations for destructive actions
- Always show a confirmation modal.
- Modal title states the action ("Terminate Priya Sharma?").
- Body states the consequence ("This will revoke access immediately and cancel 2 pending leave requests. This cannot be undone.").
- Actions: `btn-outline-secondary btn-sm` Cancel (left), `btn-danger btn-sm` Confirm (right).
- Confirm button text should be the verb, never "OK" ("Terminate employee", "Delete file").

---

## 8. Domain-specific patterns

### 8.1 Book Time Off modal
- Trigger from top-bar CTA on any page (`hx-get="/leave/new"` → modal partial).
- Step 1: Leave type — chip row of `LeaveType`s with balance badge on each ("Vacation · 18/30").
- Step 2: Date range (single date picker component that supports range).
- Step 3: Half-day toggles for start (PM) and end (AM), only visible if type allows half-day.
- Step 4: Note textarea (optional).
- Live "This will use N working days" indicator updates on any change (htmx `hx-post="/leave/preview"`).
- Submit: disabled if balance insufficient, with the reason inline.

### 8.2 Home dashboard widgets
- Grid: 3 columns on `lg+`, 2 on `md`, 1 on `sm`.
- Every widget is a `card` with title in `card-header`, body content, optional link "View all" in `card-footer`.
- Widget loading: each widget htmx-loads independently on page load (`hx-get="/widgets/..." hx-trigger="load"`). This parallelizes.
- Never render more than 6 widgets by default.

### 8.3 People list — view toggle
- Top-right button group: List · Grid · Org tree.
- Selected view persisted in `localStorage` per user, keyed by page.
- Grid view: 4 columns × N rows of avatar cards.
- Org tree view: horizontal tree rendered with an inline SVG helper — Phase 2.

### 8.4 Team Calendar
- Row per employee (avatar + name in first column, sticky).
- Columns = days of visible range, with weekend cells shaded slightly darker.
- Public holidays: full column shaded with `bg-warning-subtle` and a small holiday-name label at top.
- Leave: rounded bar spanning start-to-end, colored per LeaveType, with icon at the leading edge.
- Half-day: bar occupies 50% of the cell (top or bottom).
- Hover any bar → Bootstrap `popover` with type, dates, duration.

### 8.5 Profile page
- Left column (sticky on scroll, 280 px): avatar with edit pencil for self, name, role badge, tenure, department icon+label, manager section with mini-avatar, "My peers" avatar cluster, contact card.
- Right column: tabs (Personal · Education · Job · Documents · Tasks · Time off).
- Tab content htmx-loaded on click, URL updated.

### 8.6 Approval inbox (For Action)
- Two Bootstrap pills: Tasks · Time off requests.
- Time off pane: sub-tabs Pending (n) · Completed (n).
- Row layout: LeaveType icon · employee mini-avatar + name · dates + duration · submitted-on · note (truncated with expand) · Approve/Reject buttons.
- Approve → toast on success, row removed via htmx.
- Reject → modal for optional reason → toast → row removed.

### 8.7 File upload (Files page & Documents tab)
- Upload control is a **dropzone**, not a bare native file input: a dashed-border card region (`.upload-dropzone`) containing a centered upload icon (`bi-cloud-upload`, `fs-1 text-muted`), a bold instruction line ("Drag and drop a file here, or click to browse"), and a muted caption line naming the allowed types and size limit — same weight/size vocabulary as this app's empty states (§7.1), so the two read as one visual family.
- The real `<input type="file">` is visually hidden (`visually-hidden`, never `display:none` — it must stay in the tab order and screen-reader-visible) inside a `<label for="...">` that wraps the whole dropzone, so the entire box is clickable with no extra JS to open the file picker.
- Drag-over state (`.upload-dropzone-active`, Alpine-driven) swaps the border/background to `var(--bs-primary)` / `var(--bs-primary-bg-subtle)` — the same re-themeable variables used everywhere else (§12), never a new hardcoded color.
- Fixed height regardless of state (empty / file-chosen / drag-active) — only border, background, and the selected-filename caption change, so the layout doesn't jump.
- Sits in its own `card` above the list/table, not squeezed into the page's header row — Admin-only pages (Files) gate the whole card with `sec:authorize`; self-or-Admin pages (Documents tab) gate it with the equivalent `th:if`.
- Upload button keeps the page's existing outline style (§6 Buttons) — this is not the page's one `btn-primary` action.

---

## 9. Accessibility (WCAG 2.1 AA target)

Non-negotiable in every screen:

- All interactive elements reachable by Tab in logical order.
- Visible focus ring on every focusable element (Bootstrap default 3 px `--bs-primary` outline).
- Every `<img>` and standalone icon has `alt` or `aria-label`. Decorative icons: `aria-hidden="true"`.
- Every form control has an associated `<label>`.
- Color is never the only signal — always paired with icon or text (e.g., Approved chip has check icon + green + word "Approved").
- Contrast: text against background ≥ 4.5:1; large text (18 pt+ or 14 pt bold) ≥ 3:1. Enforce with axe-core in CI.
- Skip-to-content link at top of layout (visible on focus).
- Modals trap focus and restore it to the trigger on close.
- Toasts announced via `role="status"` + `aria-live="polite"`.
- Errors announced via `role="alert"`.

**Testing:** every merged PR runs axe-core on every page in E2E. Any Serious or Critical issue blocks merge.

---

## 10. Responsive breakpoints

Use Bootstrap defaults exclusively:

| Breakpoint | Min width | Behavior |
|---|---|---|
| `xs` | <576 px | Mobile — single column, sidebar as offcanvas |
| `sm` | ≥576 px | Small mobile — same as xs |
| `md` | ≥768 px | Tablet — sidebar visible collapsed, 2-col widgets |
| `lg` | ≥992 px | Desktop — full sidebar, 3-col widgets |
| `xl` | ≥1200 px | Large desktop |
| `xxl` | ≥1400 px | Extra large — content max-width caps at 1400 px, centered |

Design mobile-first for Employee-facing pages (profile, book time off, calendar). Admin pages can assume desktop.

---

## 11. Date, time, number formatting

- **All dates displayed in the tenant's timezone.** Store UTC, convert on render via a Thymeleaf utility (`#temporals.format(...)`).
- **Date format default:** `MMM dd, yyyy` (e.g., "Feb 01, 2019"). Configurable per tenant in Phase 2.
- **Time format default:** `HH:mm` (24-hour). Configurable per tenant later.
- **Duration:** "1 day" / "3 days" / "3.5 days" — never "3 d" or "3d".
- **Tenure:** `NY, MM, DD` on profile ("7Y 5M 20D") — matches TalentHR pattern.
- **Currency:** `#numbers.formatCurrency(amount, 'AED', locale)` with tenant locale.
- **Percentages:** whole numbers unless <1% (then one decimal). Always with `%` symbol adjacent, no space.

---

## 12. Tenant branding hooks

Two things vary per tenant, injected in `layout.html`:

1. **Logo** — `<img src="{{tenant.logoUrl}}">` in the top-bar, height 32 px. Falls back to text ("Caderly" + tenant name) if missing.
2. **Primary color** — CSS variable override in a `<style>` block:
   ```html
   <style th:if="${tenant.primaryColor != null}">
     :root { --bs-primary: [[${tenant.primaryColor}]]; }
   </style>
   ```

Nothing else per-tenant. No per-tenant font, layout, or icon swap.

---

## 13. htmx conventions

- Every htmx-triggered request has a URL that also works via GET on its own (RESTful bookmarkability where meaningful).
- `hx-target` always references an ID on the same page. Never `hx-target="body"`.
- `hx-swap="outerHTML"` for replacing components; `hx-swap="innerHTML"` for filling containers.
- `hx-boost="true"` on the layout `<body>` for progressive links — falls back to full page navigation with JS off.
- All state-changing actions include CSRF token via `hx-headers` (configured globally in a `<meta>` tag reader).
- Error handling: attach `hx-on:htmx:responseError` on triggers OR use a global `htmx.on('htmx:responseError', ...)` handler that shows a toast.
- No inline JavaScript in templates. Put JS in `caderly.js` or Alpine components.

---

## 14. Alpine.js conventions

Use Alpine only for **client-only interactivity** with no server round-trip. Examples: sidebar collapse, modal open/close, tab switching (before the tab content is fetched), form validation preview, dropdown menus.

- Prefer `x-data="{}"` inline for one-off components.
- Extract to a component in `caderly-alpine.js` if reused across three or more templates.
- Never store business data in Alpine — use htmx to sync with server.
- No Alpine plugins in MVP (no `intersect`, `mask`, etc.) — keeps bundle size trivial.

---

## 15. What NOT to do

- Do not use jQuery. htmx + Alpine covers everything jQuery did.
- Do not import a UI kit beyond Bootstrap 5 (no Bootstrap templates like AdminLTE, no shadcn ports).
- Do not create custom color variables outside §2. Always use Bootstrap semantic colors.
- Do not use Font Awesome or Material Icons. Bootstrap Icons only.
- Do not build custom form controls when a Bootstrap one exists (`form-select`, `form-check`, etc.).
- Do not use Bootstrap tooltips for critical info — they don't work on touch. Use popovers only on hover-only affordances.
- Do not autoplay animations. Prefer subtle transitions (0.15 s ease) only on hover/focus.
- Do not add fixed footers.
- Do not use browser `alert()` / `confirm()` / `prompt()`. Ever.

---

## 16. When adding a new UI

Before writing any HTML, answer:

1. Which section of PRD §24 does this correspond to?
2. Which Bootstrap components will I use (and only Bootstrap)?
3. Am I introducing any new color, spacing, or type-scale value? If so, stop and update this document first.
4. What's the empty state, loading state, and error state?
5. Is it accessible via keyboard alone?
6. What htmx interactions replace what would otherwise be JavaScript?

If you can't answer these in one line each, don't write the template yet.

---

*End of UI Guidelines — v1.0*
