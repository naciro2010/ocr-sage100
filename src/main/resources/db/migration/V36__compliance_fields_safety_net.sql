-- V36 — filet de securite : rejoue les ALTER TABLE de V35 en idempotent.
--
-- Contexte (incident deploiement Railway, 2026-04-26) : sur l'environnement
-- de production, la migration V35 a ete enregistree dans flyway_schema_history
-- (ou n'a jamais ete appliquee — diagnostic en cours), mais les colonnes
-- correspondantes manquaient en base. Au demarrage, Hibernate (ddl-auto:
-- validate) refusait de booter sur l'absence de attestation_fiscale.
-- type_attestation, faisant crasher l'app.
--
-- V36 apporte le meme schema que V35, mais en tant que NOUVELLE version
-- Flyway l'appliquera systematiquement au prochain deploiement, quel que
-- soit l'etat de V35 dans l'historique. Tous les `ADD COLUMN IF NOT EXISTS`
-- garantissent qu'aucune erreur n'est levee si V35 s'est correctement
-- appliquee : la migration devient un no-op sur les bases saines, et le
-- correctif sur les bases corrompues.

ALTER TABLE facture
    ADD COLUMN IF NOT EXISTS devise VARCHAR(8),
    ADD COLUMN IF NOT EXISTS date_reception_facture DATE;

ALTER TABLE ordre_paiement
    ADD COLUMN IF NOT EXISTS mode_paiement VARCHAR(30),
    ADD COLUMN IF NOT EXISTS devise VARCHAR(8),
    ADD COLUMN IF NOT EXISTS signataire_ordonnateur VARCHAR(255),
    ADD COLUMN IF NOT EXISTS signataire_comptable VARCHAR(255);

ALTER TABLE attestation_fiscale
    ADD COLUMN IF NOT EXISTS type_attestation VARCHAR(30);
