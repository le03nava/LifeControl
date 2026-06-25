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
-- Company Stores Table
-- ============================================
CREATE TABLE IF NOT EXISTS company_stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_zone_id UUID NOT NULL REFERENCES company_zones(id),
    store_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(50),
    address_id UUID REFERENCES addresses(id),
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
-- ============================================
-- Measure Units Table
-- ============================================
CREATE TABLE IF NOT EXISTS measure_units (
    id UUID PRIMARY KEY,
    measure_unit_name VARCHAR(100) NOT NULL,
    measure_unit_short_name VARCHAR(10) NOT NULL,
    unit_type VARCHAR(20) NOT NULL,
    sat_code VARCHAR(5) NOT NULL UNIQUE,
    description VARCHAR(255),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_measure_units_sat_code ON measure_units(sat_code);
CREATE INDEX IF NOT EXISTS idx_measure_units_type ON measure_units(unit_type);

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

-- ============================================
-- Purchase Orders Table
-- ============================================
CREATE TABLE IF NOT EXISTS purchase_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number VARCHAR(30) NOT NULL UNIQUE,
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    payment_method_id UUID NOT NULL REFERENCES payment_methods(id),
    status_id UUID NOT NULL REFERENCES statuses(id),
    comments VARCHAR(500),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_po_order_number ON purchase_orders(order_number);
CREATE INDEX IF NOT EXISTS idx_po_supplier ON purchase_orders(supplier_id);
CREATE INDEX IF NOT EXISTS idx_po_store ON purchase_orders(company_store_id);
CREATE INDEX IF NOT EXISTS idx_po_payment_method ON purchase_orders(payment_method_id);
CREATE INDEX IF NOT EXISTS idx_po_status ON purchase_orders(status_id);
CREATE INDEX IF NOT EXISTS idx_po_enabled ON purchase_orders(enabled);
CREATE INDEX IF NOT EXISTS idx_po_created_at ON purchase_orders(created_at);

-- ============================================
-- Purchase Order Details Table
-- ============================================
CREATE TABLE IF NOT EXISTS purchase_order_details (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders(id),
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price >= 0),
    total DECIMAL(12,2) NOT NULL CHECK (total >= 0),
    received_quantity INTEGER NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    comments VARCHAR(500),
    status_id UUID NOT NULL REFERENCES statuses(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pod_order ON purchase_order_details(purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_pod_product ON purchase_order_details(product_id);
CREATE INDEX IF NOT EXISTS idx_pod_status ON purchase_order_details(status_id);
CREATE INDEX IF NOT EXISTS idx_pod_enabled ON purchase_order_details(enabled);

-- ============================================
-- User Preferences Table
-- ============================================
CREATE TABLE IF NOT EXISTS user_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id VARCHAR(36) NOT NULL UNIQUE,
    company_country_id UUID REFERENCES company_countries(id),
    company_id UUID REFERENCES companies(id),
    company_region_id UUID REFERENCES company_regions(id),
    company_zone_id UUID REFERENCES company_zones(id),
    company_store_id UUID REFERENCES company_stores(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_up_keycloak_user ON user_preferences(keycloak_user_id);

-- ============================================
-- Customers Table
-- ============================================
CREATE TABLE IF NOT EXISTS customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    rfc VARCHAR(13),
    sales_channel VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customers_name ON customers(name);
CREATE INDEX IF NOT EXISTS idx_customers_sales_channel ON customers(sales_channel);
CREATE INDEX IF NOT EXISTS idx_customers_enabled ON customers(enabled);

-- ============================================
-- Product Variants Table
-- ============================================
CREATE TABLE IF NOT EXISTS product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    bar_code VARCHAR(100),
    sku VARCHAR(50),
    variant_name VARCHAR(255),
    list_price DECIMAL(12,2),
    cost_price DECIMAL(12,2),
    stock DECIMAL(12,2) DEFAULT 0,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_product_variants_bar_code ON product_variants(bar_code);
CREATE INDEX IF NOT EXISTS idx_product_variants_product ON product_variants(product_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_store ON product_variants(company_store_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_sku ON product_variants(sku);
CREATE INDEX IF NOT EXISTS idx_product_variants_enabled ON product_variants(enabled);

-- ============================================
-- Promotions Table
-- ============================================
CREATE TABLE IF NOT EXISTS promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_name VARCHAR(255) NOT NULL,
    discount_type VARCHAR(50) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    coupon_code VARCHAR(50),
    start_date TIMESTAMP,
    end_date TIMESTAMP,
    sales_channel VARCHAR(50),
    minimum_purchase_amount DECIMAL(12,2),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_promotions_coupon_code ON promotions(coupon_code);
CREATE INDEX IF NOT EXISTS idx_promotions_name ON promotions(promotion_name);
CREATE INDEX IF NOT EXISTS idx_promotions_dates ON promotions(start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_promotions_sales_channel ON promotions(sales_channel);
CREATE INDEX IF NOT EXISTS idx_promotions_enabled ON promotions(enabled);

-- ============================================
-- Shifts Table
-- ============================================
CREATE TABLE IF NOT EXISTS shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    user_id VARCHAR(255),
    opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shifts_store ON shifts(company_store_id);
CREATE INDEX IF NOT EXISTS idx_shifts_status ON shifts(status);
CREATE INDEX IF NOT EXISTS idx_shifts_opened_at ON shifts(opened_at);
CREATE INDEX IF NOT EXISTS idx_shifts_enabled ON shifts(enabled);

-- ============================================
-- Sales Orders Table
-- ============================================
CREATE TABLE IF NOT EXISTS sales_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number VARCHAR(30) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES customers(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    shift_id UUID REFERENCES shifts(id),
    user_id VARCHAR(255),
    order_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status_id UUID NOT NULL REFERENCES statuses(id),
    total_amount DECIMAL(12,2),
    payment_method_id UUID REFERENCES payment_methods(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_so_order_number ON sales_orders(order_number);
CREATE INDEX IF NOT EXISTS idx_so_customer ON sales_orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_so_store ON sales_orders(company_store_id);
CREATE INDEX IF NOT EXISTS idx_so_shift ON sales_orders(shift_id);
CREATE INDEX IF NOT EXISTS idx_so_status ON sales_orders(status_id);
CREATE INDEX IF NOT EXISTS idx_so_enabled ON sales_orders(enabled);
CREATE INDEX IF NOT EXISTS idx_so_order_date ON sales_orders(order_date);

-- ============================================
-- Sales Order Items Table
-- ============================================
CREATE TABLE IF NOT EXISTS sales_order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_order_id UUID NOT NULL REFERENCES sales_orders(id),
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    quantity DECIMAL(12,2) NOT NULL,
    list_price DECIMAL(12,2) NOT NULL,
    discount_applied DECIMAL(12,2) DEFAULT 0,
    final_price DECIMAL(12,2) NOT NULL,
    promotion_id UUID REFERENCES promotions(id),
    status_id UUID NOT NULL REFERENCES statuses(id),
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_soi_order ON sales_order_items(sales_order_id);
CREATE INDEX IF NOT EXISTS idx_soi_variant ON sales_order_items(product_variant_id);
CREATE INDEX IF NOT EXISTS idx_soi_promotion ON sales_order_items(promotion_id);
CREATE INDEX IF NOT EXISTS idx_soi_status ON sales_order_items(status_id);
CREATE INDEX IF NOT EXISTS idx_soi_enabled ON sales_order_items(enabled);

-- ============================================
-- Migration: Add payment_method_id to sales_orders
-- ============================================
ALTER TABLE sales_orders
  ADD COLUMN IF NOT EXISTS payment_method_id UUID REFERENCES payment_methods(id);

CREATE INDEX IF NOT EXISTS idx_so_payment_method ON sales_orders(payment_method_id);

-- ============================================
-- Migration: Add address columns to companies
-- ============================================
ALTER TABLE companies
  ADD COLUMN IF NOT EXISTS street VARCHAR(255),
  ADD COLUMN IF NOT EXISTS street_number VARCHAR(20),
  ADD COLUMN IF NOT EXISTS internal_number VARCHAR(20),
  ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(255),
  ADD COLUMN IF NOT EXISTS zip_code VARCHAR(20),
  ADD COLUMN IF NOT EXISTS city VARCHAR(255),
  ADD COLUMN IF NOT EXISTS state VARCHAR(255),
  ADD COLUMN IF NOT EXISTS country_id UUID REFERENCES countries(id);

CREATE INDEX IF NOT EXISTS idx_companies_country_id ON companies(country_id);
