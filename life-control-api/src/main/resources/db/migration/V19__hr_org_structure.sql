-- ============================================
-- V19 — HR org structure (departments, seniority levels, positions, salary bands, role template)
-- ============================================
-- `departments` is company-scoped: two companies may both have an "Operaciones", so every
-- uniqueness constraint is per company. `positions` hangs off a department and inherits the
-- company through it; `company_id` is never duplicated. `reports_to_position_id` is the first
-- self-referencing foreign key in this schema: the CHECK blocks only the trivial one-node cycle,
-- and the deeper cycles are validated in the service. `position_roles` is the role TEMPLATE of a
-- position — a provisioning seed, never the authority — and `role_name` is validated against a
-- declared allowlist in the service (`lc-admin` is deliberately absent).
-- Foreign keys are unnamed, matching the baseline and the V9..V18 style. The CHECKs and the
-- unique keys are named so a violation identifies the rule it broke.
-- ============================================

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id),
    department_code VARCHAR(10) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_departments_company_code UNIQUE (company_id, department_code),
    CONSTRAINT uq_departments_company_name UNIQUE (company_id, department_name)
);
CREATE INDEX idx_departments_company_id ON departments(company_id);

CREATE TABLE seniority_levels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level_code VARCHAR(10) NOT NULL UNIQUE,
    level_name VARCHAR(50) NOT NULL UNIQUE,
    rank INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_seniority_levels_rank UNIQUE (rank),
    CONSTRAINT ck_seniority_levels_rank CHECK (rank > 0)
);

CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id UUID NOT NULL REFERENCES departments(id),
    position_code VARCHAR(10) NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    reports_to_position_id UUID REFERENCES positions(id),
    display_order INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_positions_department_code UNIQUE (department_id, position_code),
    CONSTRAINT uq_positions_department_name UNIQUE (department_id, position_name),
    CONSTRAINT ck_positions_not_self_reporting
        CHECK (reports_to_position_id IS NULL OR reports_to_position_id <> id)
);
CREATE INDEX idx_positions_department_id ON positions(department_id);
CREATE INDEX idx_positions_reports_to_position_id ON positions(reports_to_position_id);

CREATE TABLE position_salary_bands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id UUID NOT NULL REFERENCES positions(id),
    seniority_level_id UUID NOT NULL REFERENCES seniority_levels(id),
    minimum_salary DECIMAL(12,2) NOT NULL,
    maximum_salary DECIMAL(12,2) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_position_salary_bands_position_level UNIQUE (position_id, seniority_level_id),
    CONSTRAINT ck_position_salary_bands_range CHECK (maximum_salary >= minimum_salary),
    CONSTRAINT ck_position_salary_bands_non_negative CHECK (minimum_salary >= 0)
);
CREATE INDEX idx_position_salary_bands_position_id ON position_salary_bands(position_id);
CREATE INDEX idx_position_salary_bands_seniority_level_id ON position_salary_bands(seniority_level_id);

CREATE TABLE position_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id UUID NOT NULL REFERENCES positions(id),
    role_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_position_roles UNIQUE (position_id, role_name)
);
CREATE INDEX idx_position_roles_position_id ON position_roles(position_id);
