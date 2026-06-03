-- ============================================
-- LifeControl API Schema
-- ============================================

-- ============================================
-- Companies Table
-- ============================================
CREATE TABLE IF NOT EXISTS companies (
    id UUID PRIMARY KEY,
    company_key VARCHAR(50) NOT NULL UNIQUE,
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

-- ============================================
-- Company Regions Table
-- ============================================
CREATE TABLE IF NOT EXISTS company_regions (
    id UUID PRIMARY KEY,
    company_country_id UUID NOT NULL REFERENCES company_countries(id),
    region_code VARCHAR(10) NOT NULL,
    region_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(company_country_id, region_code)
);

CREATE INDEX IF NOT EXISTS idx_cr_company_country ON company_regions(company_country_id);

-- ============================================
-- Company Zones Table
-- ============================================
CREATE TABLE IF NOT EXISTS company_zones (
    id UUID PRIMARY KEY,
    company_region_id UUID NOT NULL REFERENCES company_regions(id),
    zone_code VARCHAR(10) NOT NULL,
    zone_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(company_region_id, zone_code)
);

CREATE INDEX IF NOT EXISTS idx_cz_region ON company_zones(company_region_id);

-- ============================================
-- Company Store Addresses Table
-- ============================================
CREATE TABLE IF NOT EXISTS company_store_addresses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    street VARCHAR(255) NOT NULL,
    street_number VARCHAR(20) NOT NULL,
    internal_number VARCHAR(20),
    neighborhood VARCHAR(255) NOT NULL,
    zip_code VARCHAR(20) NOT NULL,
    city VARCHAR(255) NOT NULL,
    state VARCHAR(255) NOT NULL,
    country_id UUID NOT NULL REFERENCES countries(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_company_store_addresses_country_id ON company_store_addresses(country_id);

-- ============================================
-- Company Stores Table
-- ============================================
CREATE TABLE IF NOT EXISTS company_stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_zone_id UUID NOT NULL REFERENCES company_zones(id),
    store_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(50),
    address_id UUID REFERENCES company_store_addresses(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(store_name, company_zone_id)
);

CREATE INDEX IF NOT EXISTS idx_company_stores_company_zone_id ON company_stores(company_zone_id);
CREATE INDEX IF NOT EXISTS idx_company_stores_address_id ON company_stores(address_id);

-- ============================================
-- Suppliers Table
-- ============================================
CREATE TABLE IF NOT EXISTS suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_name VARCHAR(255) NOT NULL,
    razon_social VARCHAR(255),
    rfc VARCHAR(13) NOT NULL UNIQUE,
    email VARCHAR(255),
    phone_number VARCHAR(50),
    street VARCHAR(255),
    street_number VARCHAR(20),
    neighborhood VARCHAR(255),
    zip_code VARCHAR(20),
    city VARCHAR(255),
    state VARCHAR(255),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_suppliers_name ON suppliers(supplier_name);
CREATE INDEX IF NOT EXISTS idx_suppliers_rfc ON suppliers(rfc);

-- ============================================
-- Activity Process Reference Table
-- ============================================
CREATE TABLE IF NOT EXISTS activity_processes (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- Products Table
-- ============================================
CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY,
    sku VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    short_name VARCHAR(100),
    sat_code VARCHAR(20),
    product_type VARCHAR(50),
    attributes JSONB,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku);
CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);
CREATE INDEX IF NOT EXISTS idx_products_attributes ON products USING GIN (attributes);

-- ============================================
-- Product Suppliers Table (M:N relationship)
-- ============================================
CREATE TABLE IF NOT EXISTS product_suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id),
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    purchase_cost DECIMAL(12,2),
    main BOOLEAN DEFAULT false,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(product_id, supplier_id)
);

CREATE INDEX IF NOT EXISTS idx_ps_product ON product_suppliers(product_id);
CREATE INDEX IF NOT EXISTS idx_ps_supplier ON product_suppliers(supplier_id);

-- ============================================
-- Activity Event Reference Table
-- ============================================
CREATE TABLE IF NOT EXISTS activity_events (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- Activity Log Table (immutable audit trail)
-- ============================================
CREATE TABLE IF NOT EXISTS activity_logs (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255),
    username VARCHAR(255),
    activity_process_id UUID NOT NULL REFERENCES activity_processes(id),
    activity_event_id UUID NOT NULL REFERENCES activity_events(id),
    http_method VARCHAR(10) NOT NULL,
    http_status INTEGER NOT NULL,
    request_path VARCHAR(500) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_activity_logs_user_id ON activity_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_activity_logs_created_at ON activity_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_activity_logs_process ON activity_logs(activity_process_id);
CREATE INDEX IF NOT EXISTS idx_activity_logs_event ON activity_logs(activity_event_id);

-- ============================================
-- Status Types Table
-- ============================================
CREATE TABLE IF NOT EXISTS status_types (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status_type_name VARCHAR(100) NOT NULL UNIQUE,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_status_types_name ON status_types(status_type_name);

-- ============================================
-- Statuses Table
-- ============================================
CREATE TABLE IF NOT EXISTS statuses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status_name VARCHAR(100) NOT NULL,
    status_type_id UUID NOT NULL REFERENCES status_types(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(status_type_id, status_name)
);

CREATE INDEX IF NOT EXISTS idx_statuses_type ON statuses(status_type_id);
CREATE INDEX IF NOT EXISTS idx_statuses_name ON statuses(status_name);

-- ============================================
-- Payment Methods Table
-- ============================================
CREATE TABLE IF NOT EXISTS payment_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_method_name VARCHAR(100) NOT NULL UNIQUE,
    payment_method_short_name VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_methods_name ON payment_methods(payment_method_name);
CREATE INDEX IF NOT EXISTS idx_payment_methods_enabled ON payment_methods(enabled);
