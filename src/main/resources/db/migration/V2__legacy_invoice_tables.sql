-- V2: Create legacy invoice tables required by JPA entities
-- These tables were dropped in V1 but the entities still exist

CREATE TABLE IF NOT EXISTS invoices (
    id               BIGSERIAL PRIMARY KEY,
    file_name        VARCHAR(500) NOT NULL,
    file_path        VARCHAR(1000) NOT NULL,
    status           VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    raw_text         TEXT,
    supplier_name    VARCHAR(500),
    supplier_ice     VARCHAR(20),
    supplier_if      VARCHAR(50),
    supplier_rc      VARCHAR(100),
    supplier_patente VARCHAR(100),
    supplier_cnss    VARCHAR(100),
    supplier_address TEXT,
    supplier_city    VARCHAR(200),
    client_name      VARCHAR(500),
    client_ice       VARCHAR(20),
    invoice_number   VARCHAR(200),
    invoice_date     DATE,
    amount_ht        NUMERIC(15,2),
    tva_rate         NUMERIC(5,2),
    amount_tva       NUMERIC(15,2),
    amount_ttc       NUMERIC(15,2),
    discount_amount  NUMERIC(15,2),
    discount_percent NUMERIC(5,2),
    currency         VARCHAR(10) DEFAULT 'MAD',
    payment_method   VARCHAR(100),
    payment_due_date DATE,
    bank_name        VARCHAR(300),
    bank_rib         VARCHAR(50),
    sage_synced      BOOLEAN NOT NULL DEFAULT false,
    sage_sync_date   TIMESTAMP,
    sage_reference   VARCHAR(200),
    error_message    TEXT,
    erp_type         VARCHAR(50) DEFAULT 'SAGE_1000',
    ocr_engine       VARCHAR(50),
    ocr_confidence   DOUBLE PRECISION,
    ocr_page_count   INTEGER,
    ai_used          BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS invoice_line_items (
    id             BIGSERIAL PRIMARY KEY,
    invoice_id     BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    line_number    INTEGER NOT NULL,
    description    TEXT,
    quantity       NUMERIC(15,3),
    unit           VARCHAR(50),
    unit_price_ht  NUMERIC(15,2),
    tva_rate       NUMERIC(5,2),
    tva_amount     NUMERIC(15,2),
    total_ht       NUMERIC(15,2),
    total_ttc      NUMERIC(15,2),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invoices_supplier ON invoices(supplier_name);
CREATE INDEX IF NOT EXISTS idx_invoices_status ON invoices(status);
CREATE INDEX IF NOT EXISTS idx_invoices_created_at ON invoices(created_at);
CREATE INDEX IF NOT EXISTS idx_line_items_invoice ON invoice_line_items(invoice_id);
