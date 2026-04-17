-- Postgres full-text search index over OCR'd text and structured fields.
-- We stage a tsvector column populated by trigger so we never block ingestion
-- while tsvector_to_array runs, and use a GIN index for instant LIKE-style
-- search across millions of rows.
--
-- 'simple' dictionary keeps it idempotent for French/Arabic mixed content
-- (no stemming surprises). For richer ranking later, add 'french' alongside.

ALTER TABLE document
    ADD COLUMN IF NOT EXISTS search_tsv tsvector;

CREATE INDEX IF NOT EXISTS idx_document_search_tsv
    ON document USING GIN (search_tsv);

CREATE OR REPLACE FUNCTION document_search_tsv_update() RETURNS trigger AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('simple', coalesce(NEW.nom_fichier, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(NEW.texte_extrait, '')), 'C');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS document_search_tsv_trigger ON document;
CREATE TRIGGER document_search_tsv_trigger
    BEFORE INSERT OR UPDATE OF nom_fichier, texte_extrait
    ON document
    FOR EACH ROW EXECUTE FUNCTION document_search_tsv_update();

-- Backfill rows that already have inline texte_extrait (legacy pre-V14).
-- Rows persisted via ExtractStorage have texte_extrait NULL — the search will
-- only match on filename for those, which is acceptable until we add an
-- async backfill job (out of scope here).
UPDATE document
SET search_tsv =
    setweight(to_tsvector('simple', coalesce(nom_fichier, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(texte_extrait, '')), 'C')
WHERE search_tsv IS NULL;
