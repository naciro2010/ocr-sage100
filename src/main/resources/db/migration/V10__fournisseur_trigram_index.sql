-- Trigram index for fast LIKE '%fournisseur%' search
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_dossier_fournisseur_trgm
    ON dossier_paiement USING gin(fournisseur gin_trgm_ops);
