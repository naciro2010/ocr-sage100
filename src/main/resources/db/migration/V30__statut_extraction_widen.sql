-- Probleme observe en prod : PSQLException "value too long for type character
-- varying(20)" sur document.statut_extraction lors de l'enregistrement du statut
-- REVUE_HUMAINE_REQUISE (21 caracteres) introduit par PR #72.
--
-- Correction : elargir a VARCHAR(30) et mettre a jour la contrainte CHECK pour
-- inclure toutes les valeurs actuelles de l'enum StatutExtraction.
--
-- Strategie locks : ALTER COLUMN TYPE VARCHAR(N > ancien) est metadata-only
-- depuis Postgres 9.2 (pas de rewrite). Pour la CHECK, on utilise NOT VALID
-- puis VALIDATE : la validation prend un SHARE UPDATE EXCLUSIVE lock au lieu
-- d'ACCESS EXCLUSIVE, les extractions concurrentes continuent de tourner
-- pendant la verification des lignes existantes.
ALTER TABLE document DROP CONSTRAINT IF EXISTS document_statut_extraction_check;
ALTER TABLE document ALTER COLUMN statut_extraction TYPE VARCHAR(30);
ALTER TABLE document ADD CONSTRAINT document_statut_extraction_check
    CHECK (statut_extraction IN (
        'EN_ATTENTE', 'EN_COURS', 'EXTRAIT', 'ERREUR', 'REVUE_HUMAINE_REQUISE'
    )) NOT VALID;
ALTER TABLE document VALIDATE CONSTRAINT document_statut_extraction_check;
