-- V5: Add commentaire and manual override fields to validation results
ALTER TABLE resultat_validation ADD COLUMN IF NOT EXISTS commentaire TEXT;
ALTER TABLE resultat_validation ADD COLUMN IF NOT EXISTS statut_original VARCHAR(20);
ALTER TABLE resultat_validation ADD COLUMN IF NOT EXISTS corrige_par VARCHAR(200);
ALTER TABLE resultat_validation ADD COLUMN IF NOT EXISTS date_correction TIMESTAMP;
