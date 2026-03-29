-- Moroccan fiscal identifiers for supplier
ALTER TABLE invoices ADD COLUMN supplier_ice      VARCHAR(50);
ALTER TABLE invoices ADD COLUMN supplier_if       VARCHAR(50);
ALTER TABLE invoices ADD COLUMN supplier_rc       VARCHAR(50);
ALTER TABLE invoices ADD COLUMN supplier_patente  VARCHAR(50);
ALTER TABLE invoices ADD COLUMN supplier_cnss     VARCHAR(50);
ALTER TABLE invoices ADD COLUMN supplier_address  TEXT;
ALTER TABLE invoices ADD COLUMN supplier_city     VARCHAR(200);

-- Client (our company) identifiers extracted
ALTER TABLE invoices ADD COLUMN client_name       VARCHAR(500);
ALTER TABLE invoices ADD COLUMN client_ice        VARCHAR(50);

-- Payment info
ALTER TABLE invoices ADD COLUMN payment_method    VARCHAR(100);
ALTER TABLE invoices ADD COLUMN payment_due_date  DATE;
ALTER TABLE invoices ADD COLUMN bank_name         VARCHAR(300);
ALTER TABLE invoices ADD COLUMN bank_rib          VARCHAR(50);

-- TVA details (Morocco: 0%, 7%, 10%, 14%, 20%)
ALTER TABLE invoices ADD COLUMN tva_rate          NUMERIC(5, 2);

-- Discount
ALTER TABLE invoices ADD COLUMN discount_amount   NUMERIC(15, 2);
ALTER TABLE invoices ADD COLUMN discount_percent  NUMERIC(5, 2);

-- Line items table
CREATE TABLE invoice_line_items (
    id              BIGSERIAL PRIMARY KEY,
    invoice_id      BIGINT         NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    line_number     INT            NOT NULL,
    description     TEXT,
    quantity        NUMERIC(15, 3),
    unit            VARCHAR(50),
    unit_price_ht   NUMERIC(15, 2),
    tva_rate        NUMERIC(5, 2),
    tva_amount      NUMERIC(15, 2),
    total_ht        NUMERIC(15, 2),
    total_ttc       NUMERIC(15, 2),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_line_items_invoice ON invoice_line_items(invoice_id);
