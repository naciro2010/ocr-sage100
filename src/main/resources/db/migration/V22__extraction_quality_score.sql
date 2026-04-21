-- Score qualite composite (0-100) de chaque extraction + liste des champs
-- obligatoires manquants. Nullable : rempli a partir de la premiere extraction
-- post-deployment, les documents existants restent NULL jusqu'a re-extraction.
ALTER TABLE document
    ADD COLUMN extraction_quality_score INTEGER NULL,
    ADD COLUMN missing_mandatory_fields TEXT NULL;

CREATE INDEX idx_document_quality_score
    ON document (extraction_quality_score)
    WHERE extraction_quality_score IS NOT NULL;
