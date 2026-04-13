-- Allow multiple factures per dossier
ALTER TABLE facture DROP CONSTRAINT IF EXISTS facture_dossier_id_key;

-- Add documentIds column to resultat_validation for source traceability
ALTER TABLE resultat_validation ADD COLUMN IF NOT EXISTS document_ids TEXT;
