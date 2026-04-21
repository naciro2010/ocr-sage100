-- V28 : Ajoute les references du document source sur l'engagement.
-- Ces colonnes permettent de retrouver et re-telecharger le PDF du
-- marche/BC cadre/contrat qui a servi a creer l'engagement.
--
-- Ces documents ne sont PAS rattaches a un dossier (ils n'ont pas de
-- dossier_id) : ils existent au niveau engagement uniquement.

ALTER TABLE engagement ADD COLUMN source_document_path VARCHAR(1000);
ALTER TABLE engagement ADD COLUMN source_document_name VARCHAR(500);
ALTER TABLE engagement ADD COLUMN source_document_hash VARCHAR(64);

CREATE INDEX idx_engagement_source_hash ON engagement(source_document_hash);
