-- Allow CHECKLIST as a valid source in resultat_validation
ALTER TABLE resultat_validation DROP CONSTRAINT IF EXISTS resultat_validation_source_check;
ALTER TABLE resultat_validation ADD CONSTRAINT resultat_validation_source_check
    CHECK (source IN ('DETERMINISTE', 'LLM', 'CHECKLIST'));
