-- =====================================================
-- V26: Ajoute la source "ENGAGEMENT" aux resultats de validation.
-- Les regles R-E01..E05, R-M01..R-M07, R-B01..R-B04, R-C01..R-C05
-- produites par les EngagementValidator utilisent cette source.
-- =====================================================

ALTER TABLE resultat_validation DROP CONSTRAINT IF EXISTS resultat_validation_source_check;
ALTER TABLE resultat_validation ADD CONSTRAINT resultat_validation_source_check
    CHECK (source IN ('DETERMINISTE', 'LLM', 'CHECKLIST', 'CUSTOM', 'ENGAGEMENT'));
