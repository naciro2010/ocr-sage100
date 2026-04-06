CREATE TABLE app_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE invoices ADD COLUMN ai_used BOOLEAN NOT NULL DEFAULT false;

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('ai.enabled', 'false'),
    ('ai.api_key', ''),
    ('ai.model', 'claude-sonnet-4-6'),
    ('ai.base_url', 'https://api.anthropic.com'),
    ('erp.active_type', 'SAGE_1000'),
    ('erp.sage1000.base_url', ''),
    ('erp.sage1000.api_key', ''),
    ('erp.sage1000.company_code', ''),
    ('erp.sage1000.timeout', '30'),
    ('erp.sagex3.base_url', ''),
    ('erp.sagex3.client_id', ''),
    ('erp.sagex3.client_secret', ''),
    ('erp.sagex3.folder', 'MAROC'),
    ('erp.sagex3.pool_alias', 'x3'),
    ('erp.sage50.base_url', ''),
    ('erp.sage50.username', ''),
    ('erp.sage50.password', ''),
    ('erp.sage50.company_file', ''),
    ('erp.sage50.journal_code', 'ACH'),
    ('erp.sage50.fiscal_year', '2024');
