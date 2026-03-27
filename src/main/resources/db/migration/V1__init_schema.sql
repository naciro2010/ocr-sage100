CREATE TABLE invoices (
    id              BIGSERIAL PRIMARY KEY,
    file_name       VARCHAR(500)   NOT NULL,
    file_path       VARCHAR(1000)  NOT NULL,
    status          VARCHAR(50)    NOT NULL DEFAULT 'UPLOADED',
    raw_text        TEXT,
    supplier_name   VARCHAR(500),
    invoice_number  VARCHAR(200),
    invoice_date    DATE,
    amount_ht       NUMERIC(15, 2),
    amount_tva      NUMERIC(15, 2),
    amount_ttc      NUMERIC(15, 2),
    currency        VARCHAR(10)    DEFAULT 'MAD',
    sage_synced     BOOLEAN        NOT NULL DEFAULT FALSE,
    sage_sync_date  TIMESTAMP,
    sage_reference  VARCHAR(200),
    error_message   TEXT,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_invoices_supplier ON invoices(supplier_name);
CREATE INDEX idx_invoices_date ON invoices(invoice_date);
CREATE INDEX idx_invoices_sage_synced ON invoices(sage_synced);
