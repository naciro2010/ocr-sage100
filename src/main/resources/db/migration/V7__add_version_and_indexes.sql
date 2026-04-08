-- Optimistic locking
ALTER TABLE dossier_paiement ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_dossier_statut ON dossier_paiement(statut);
CREATE INDEX IF NOT EXISTS idx_dossier_type ON dossier_paiement(type);
CREATE INDEX IF NOT EXISTS idx_dossier_reference ON dossier_paiement(reference);
CREATE INDEX IF NOT EXISTS idx_dossier_date_creation ON dossier_paiement(date_creation DESC);
CREATE INDEX IF NOT EXISTS idx_dossier_fournisseur ON dossier_paiement(fournisseur);
