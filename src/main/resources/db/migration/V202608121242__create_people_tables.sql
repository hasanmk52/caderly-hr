-- Employee + sub-entities (PRD §21, sub-phase 1.4). All tenant-scoped: tenant_id NOT NULL
-- plus the RLS template block from V202607241000 (CLAUDE.md §5 rules 1 and 2).
--
-- Encrypted columns (government_id.id_number, bank_detail.*, employee.base_compensation)
-- are `bytea`, not `varchar` — CryptoConverter (common package, ADR 0008) stores a random
-- 12-byte GCM IV prepended to ciphertext+tag as raw bytes, not text.

CREATE TABLE employee (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  -- Nullable: an employee exists (and can be assigned a department, manager, etc.) before
  -- the invite is accepted and an app_user row is even guaranteed to still exist.
  user_id uuid REFERENCES app_user (id),
  employee_code varchar(50),
  first_name varchar(100) NOT NULL,
  last_name varchar(100) NOT NULL,
  email varchar(255) NOT NULL,
  phone varchar(30),
  birth_date date,
  gender varchar(20),
  marital_status varchar(20),
  nationality varchar(100),
  citizenship varchar(100),
  address_line1 varchar(200),
  address_line2 varchar(200),
  city varchar(100),
  country varchar(100),
  postal_code varchar(20),
  hire_date date,
  termination_date date,
  employment_type varchar(20), -- FULL_TIME, PART_TIME, CONTRACT, INTERN
  status varchar(20) NOT NULL, -- INVITED, ACTIVE, TERMINATED
  department_id uuid REFERENCES department (id),
  manager_id uuid REFERENCES employee (id),
  job_title varchar(150),
  work_location varchar(150),
  working_hours_per_day numeric(4, 2) NOT NULL DEFAULT 8.0,
  currency varchar(3),
  -- AES-256-GCM via CryptoConverter (ADR 0008); optional per PRD §21.
  base_compensation_encrypted bytea,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);

CREATE INDEX idx_employee_department_id ON employee (department_id);
CREATE INDEX idx_employee_manager_id ON employee (manager_id);
CREATE INDEX idx_employee_user_id ON employee (user_id);

-- Now that `employee` exists, the FK reserved by 1.3 (see V202608071000) can be added.
ALTER TABLE department
  ADD CONSTRAINT fk_department_head_employee FOREIGN KEY (head_employee_id) REFERENCES employee (id);

CREATE TABLE employee_status_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  status varchar(20) NOT NULL,
  employment_type varchar(20),
  effective_from date NOT NULL,
  effective_to date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_status_history_employee_id ON employee_status_history (employee_id);

CREATE TABLE employee_manager_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  manager_id uuid REFERENCES employee (id),
  effective_from date NOT NULL,
  effective_to date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_employee_manager_history_employee_id ON employee_manager_history (employee_id);

CREATE TABLE education (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  institution varchar(200),
  degree varchar(150),
  field varchar(150),
  start_year int,
  end_year int,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_education_employee_id ON education (employee_id);

CREATE TABLE emergency_contact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  name varchar(150) NOT NULL,
  relationship varchar(50),
  phone varchar(30),
  email varchar(255),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_emergency_contact_employee_id ON emergency_contact (employee_id);

CREATE TABLE government_id (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  id_type varchar(50) NOT NULL, -- PASSPORT, NATIONAL_ID, VISA, EMIRATES_ID, OTHER
  -- AES-256-GCM via CryptoConverter (ADR 0008, CLAUDE.md §6 A02).
  id_number_encrypted bytea NOT NULL,
  country varchar(100),
  issue_date date,
  expiry_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_government_id_employee_id ON government_id (employee_id);

CREATE TABLE bank_detail (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  bank_name varchar(200),
  -- AES-256-GCM via CryptoConverter (ADR 0008, CLAUDE.md §6 A02).
  account_name_encrypted bytea,
  account_number_encrypted bytea,
  iban_encrypted bytea,
  swift_bic varchar(20),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  -- One bank detail row per employee (PRD §21).
  UNIQUE (tenant_id, employee_id)
);

CREATE TABLE benefit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant (id),
  employee_id uuid NOT NULL REFERENCES employee (id),
  name varchar(150),
  category varchar(100),
  start_date date,
  end_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_benefit_employee_id ON benefit (employee_id);

-- ============================================================================
-- RLS — the template block from V202607241000, applied to each table above.
-- FORCE is critical: the app role OWNS these tables, and Postgres exempts table
-- owners from RLS unless it is forced.
-- current_setting(..., true) returns NULL when app.tenant_id is unset, so each
-- policy denies ALL rows by default — no tenant context means no data.
-- ============================================================================

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE employee_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_status_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_status_history
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE employee_manager_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_manager_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_manager_history
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE education ENABLE ROW LEVEL SECURITY;
ALTER TABLE education FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON education
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE emergency_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE emergency_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON emergency_contact
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE government_id ENABLE ROW LEVEL SECURITY;
ALTER TABLE government_id FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON government_id
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE bank_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_detail
  USING (tenant_id::text = current_setting('app.tenant_id', true));

ALTER TABLE benefit ENABLE ROW LEVEL SECURITY;
ALTER TABLE benefit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON benefit
  USING (tenant_id::text = current_setting('app.tenant_id', true));
