-- Sub-phase 1.10 (PRD §6.9 FR-9.1-9.3, §17). Three unrelated-looking changes that are all
-- the same change: the notification catalogue stops being ad-hoc.

-- 1. Which event produced a row. Nullable because the rows already in the table predate the
-- catalogue and belong to no event. Feeds the Admin viewer's filter and the daily jobs'
-- "did I already queue this today?" guard.
ALTER TABLE email_outbox ADD COLUMN event_type varchar(40);

-- The Admin viewer's query. email_outbox has no RLS (see this table's own migration header),
-- so tenant_id is a hand-written predicate here, not a policy — index it as the leading column.
CREATE INDEX idx_email_outbox_admin ON email_outbox (tenant_id, created_at DESC);

-- The sweep jobs' idempotency probe: one exists() per recipient per day. Without it a restart
-- across the cron minute re-sends every birthday and holiday reminder.
CREATE INDEX idx_email_outbox_dedupe ON email_outbox (tenant_id, event_type, to_email, created_at);

-- 2. FR-9.3's per-tenant category switches. Columns on tenant rather than a settings table:
-- the category list is closed (PRD §17.2 names every event), and this is where weekend_days
-- and timezone already live. Transactional mail (invite, reset, the four leave events) has no
-- switch at all — it is not a "category", it is the product working.
--
-- Birthday and work anniversary default to FALSE: PRD §17.2 marks both "(opt-in per tenant)".
-- The other two default TRUE because a holiday everyone forgot and an ID that expired are the
-- failures these reminders exist to prevent.
ALTER TABLE tenant
  ADD COLUMN notify_holiday_reminder boolean NOT NULL DEFAULT true,
  ADD COLUMN notify_document_expiry  boolean NOT NULL DEFAULT true,
  ADD COLUMN notify_birthday         boolean NOT NULL DEFAULT false,
  ADD COLUMN notify_work_anniversary boolean NOT NULL DEFAULT false;

-- 3. Branding is one Caderly brand, not a per-tenant colour (ADR 0016). Deliberately NOT
-- additive, which CLAUDE.md §12 requires be called out: the column was dead — nothing read it
-- in Java, CSS or Thymeleaf — and every row held the stale Tailwind indigo default rather than
-- the actual brand petrol, so dropping it destroys no information anyone was using.
-- docs/design-system/guidelines/BRAND.source.md §9.3 change #1 asks for exactly this.
-- tenant.logo_url stays: a tenant logo is still a guest on our chrome (BRAND.source.md §8.1).
ALTER TABLE tenant DROP COLUMN primary_color;
