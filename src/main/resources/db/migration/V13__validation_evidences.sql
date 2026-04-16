-- =====================================================
-- V13: Structured evidences on validation results
-- Stores the precise field / document / value that each rule compared,
-- enabling field-level UX and inline corrections.
-- =====================================================

ALTER TABLE resultat_validation
    ADD COLUMN IF NOT EXISTS evidences JSONB;

-- Fast lookup by rule code (used by correct-and-rerun)
CREATE INDEX IF NOT EXISTS idx_validation_regle ON resultat_validation(regle);
