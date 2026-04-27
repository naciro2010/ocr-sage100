-- V37 — Ajoute la source "CUSTOM_BATCH" aux resultats de validation.
--
-- Contexte (incident production, 2026-04-27) : CustomRuleService.evaluateBatch()
-- insere des ResultatValidation avec source = "CUSTOM_BATCH" pour distinguer les
-- regles CUSTOM evaluees en batch (un seul appel Claude pour N regles) des
-- regles evaluees une par une (source = "CUSTOM"). La contrainte CHECK posee
-- par V26 n'incluait pas cette valeur, ce qui faisait echouer chaque "Lancer
-- les controles" avec : new row for relation "resultat_validation" violates
-- check constraint "resultat_validation_source_check".

ALTER TABLE resultat_validation DROP CONSTRAINT IF EXISTS resultat_validation_source_check;
ALTER TABLE resultat_validation ADD CONSTRAINT resultat_validation_source_check
    CHECK (source IN ('DETERMINISTE', 'LLM', 'CHECKLIST', 'CUSTOM', 'CUSTOM_BATCH', 'ENGAGEMENT'));
