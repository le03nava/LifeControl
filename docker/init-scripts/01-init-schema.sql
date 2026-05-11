-- ============================================
-- PostgreSQL Initialization Script
-- LifeControl API Schema
-- ============================================
-- This script runs on first container startup (empty volume)
-- ============================================

-- ============================================
-- API Users Table
-- ============================================
CREATE TABLE IF NOT EXISTS api_users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    lastname VARCHAR(255),
    phone VARCHAR(50),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_api_users_username ON api_users(username);
CREATE INDEX IF NOT EXISTS idx_api_users_email ON api_users(email);

-- ============================================
-- Companies Table
-- ============================================
CREATE TABLE IF NOT EXISTS companies (
    id UUID PRIMARY KEY,
    company_id INTEGER NOT NULL UNIQUE,
    company_key VARCHAR(255) NOT NULL UNIQUE,
    company_name VARCHAR(255) NOT NULL,
    tipo_persona_id INTEGER,
    razon_social VARCHAR(255),
    rfc VARCHAR(13) NOT NULL UNIQUE,
    phone VARCHAR(50),
    email VARCHAR(255),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_companies_company_id ON companies(company_id);
CREATE INDEX IF NOT EXISTS idx_companies_company_key ON companies(company_key);
CREATE INDEX IF NOT EXISTS idx_companies_rfc ON companies(rfc);

-- ============================================
-- Countries Table
-- ============================================
CREATE TABLE IF NOT EXISTS countries (
    id UUID PRIMARY KEY,
    country_code VARCHAR(2) NOT NULL UNIQUE,
    country_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_countries_code ON countries(country_code);

-- ============================================
-- Company Countries Table (M:N relationship)
-- ============================================
CREATE TABLE IF NOT EXISTS company_countries (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    country_id UUID NOT NULL REFERENCES countries(id),
    local_alias VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(company_id, country_id)
);

CREATE INDEX IF NOT EXISTS idx_cc_company ON company_countries(company_id);
CREATE INDEX IF NOT EXISTS idx_cc_country ON company_countries(country_id);
