-- V3: ERP configuration, validation results, and batch tracking

-- ERP configuration table
CREATE TABLE erp_config (
    id BIGSERIAL PRIMARY KEY,
    erp_type VARCHAR(20) NOT NULL DEFAULT 'SAGE_1000',
    active BOOLEAN NOT NULL DEFAULT true,
    config_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO erp_config (erp_type, active) VALUES ('SAGE_1000', true);

-- Add ERP type tracking to invoices
ALTER TABLE invoices ADD COLUMN erp_type VARCHAR(20) DEFAULT 'SAGE_1000';

-- Validation results table
CREATE TABLE validation_results (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    valid BOOLEAN NOT NULL,
    validated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE validation_messages (
    id BIGSERIAL PRIMARY KEY,
    validation_result_id BIGINT NOT NULL REFERENCES validation_results(id) ON DELETE CASCADE,
    field VARCHAR(100),
    message TEXT NOT NULL,
    severity VARCHAR(10) NOT NULL DEFAULT 'ERROR'
);

CREATE INDEX idx_validation_results_invoice ON validation_results(invoice_id);

-- Batch processing tracking
CREATE TABLE batch_jobs (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(20) NOT NULL,
    total_items INT NOT NULL DEFAULT 0,
    successful INT NOT NULL DEFAULT 0,
    failed INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE batch_job_items (
    id BIGSERIAL PRIMARY KEY,
    batch_job_id BIGINT NOT NULL REFERENCES batch_jobs(id) ON DELETE CASCADE,
    invoice_id BIGINT REFERENCES invoices(id),
    file_name VARCHAR(500),
    success BOOLEAN,
    error_message TEXT,
    sage_reference VARCHAR(100)
);

CREATE INDEX idx_batch_job_items_job ON batch_job_items(batch_job_id);
