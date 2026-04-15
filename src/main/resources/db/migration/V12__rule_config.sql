CREATE TABLE IF NOT EXISTS rule_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    regle VARCHAR(10) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS dossier_rule_override (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    regle VARCHAR(10) NOT NULL,
    enabled BOOLEAN NOT NULL,
    UNIQUE(dossier_id, regle)
);

CREATE INDEX idx_dossier_rule_override_dossier ON dossier_rule_override(dossier_id);
