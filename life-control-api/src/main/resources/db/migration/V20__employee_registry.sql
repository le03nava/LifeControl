-- ============================================
-- V20 — Employee registry (companies.email_domain, employees, EMPLOYEE_STATUS seed)
-- ============================================
-- This migration adds the first real person->company model to the schema: every previous person
-- reference is a free-text Keycloak `sub` in a VARCHAR column with no FK and no existence check.
-- `employees` is the HR record and is NOT a login: `keycloak_user_id` is a nullable link to an
-- existing Keycloak account, so an employee without an account is legal and an account without an
-- employee is legal too. It is written only by the access-provisioning flow, never by the employee
-- record path — the column is the security precondition of the auto-apply policy (record T17).
-- `employees.email` is generated from the person's names over `companies.email_domain`, and it is
-- simultaneously the Keycloak username; the generation rule is a one-way door, because renaming an
-- email is renaming a login (record T9). It is editable while `keycloak_user_id IS NULL` and frozen
-- afterwards.
-- `companies.email_domain` is nullable, and its absence fails the employee create path closed (a
-- company without a domain cannot generate an address; the operator types one instead).
-- NOT in this migration: `employee_contracts`, the `ex_employee_contracts_no_overlap` exclusion
-- constraint, and the `btree_gist` extension they need. All three arrive with the record's W2, which
-- is why no `CREATE EXTENSION` statement appears in this file.
-- Foreign keys are unnamed, matching the baseline and the V9..V19 style. The CHECKs and the unique
-- keys are named so a violation identifies the rule it broke.
-- ============================================

-- The corporate email domain of a company: the address an employee identity is built on.
ALTER TABLE companies ADD COLUMN email_domain VARCHAR(255);

CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id), -- the single-valued company is the DECIDED ceiling of company-scope-local-fallback's D4 = no (2026-10-02), not an accident of the design
    employee_number VARCHAR(30) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    paternal_last_name VARCHAR(100) NOT NULL,
    maternal_last_name VARCHAR(100),
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50),
    birth_date DATE NOT NULL,
    hire_date DATE NOT NULL,
    termination_date DATE,
    address_id UUID REFERENCES addresses(id),
    employment_status_id UUID NOT NULL REFERENCES statuses(id),
    keycloak_user_id VARCHAR(36) UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_employees_company_number UNIQUE (company_id, employee_number),
    CONSTRAINT uq_employees_company_email UNIQUE (company_id, email),
    CONSTRAINT ck_employees_termination_after_hire
        CHECK (termination_date IS NULL OR termination_date >= hire_date),
    CONSTRAINT ck_employees_birth_before_hire CHECK (birth_date < hire_date)
);
CREATE INDEX idx_employees_company_id ON employees(company_id);
CREATE INDEX idx_employees_employment_status_id ON employees(employment_status_id);
CREATE INDEX idx_employees_address_id ON employees(address_id);

-- The EMPLOYEE_STATUS family, with the idempotent natural-key guard V3/V12/V18 use.
INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'EMPLOYEE_STATUS', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('EMPLOYEE_STATUS'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Active', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Active'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Inactive', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Inactive'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'OnLeave', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('OnLeave'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Terminated', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('EMPLOYEE_STATUS')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Terminated'));
