-- Offload OCR raw text out of the document row to keep JPA responses small
-- and reduce Postgres storage pressure. New documents persist the extract in
-- external storage (filesystem / S3-compatible) and keep only a pointer here.
-- Legacy rows with inline texte_extrait still work: the service falls back to
-- it when texte_extrait_key is NULL.

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS texte_extrait_key VARCHAR(500);
