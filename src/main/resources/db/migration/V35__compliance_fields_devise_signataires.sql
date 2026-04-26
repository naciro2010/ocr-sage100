-- V35 — champs de conformite reglementaire MA pour activer / affiner :
--   * R27 (devise MAD obligatoire — CGNC + Loi 9-88)
--   * R26 (plafond paiement especes 5kMAD — CGI art. 193-ter)
--   * R25 (delai 60j paiement marche public — Decret 2-22-431 art. 159)
--   * R31 (separation des pouvoirs ordonnateur / comptable —
--          Decret 2-22-431 art. 21)
--   * R18 nuance par type d'attestation (Circulaire DGI 717)
--
-- Tous les champs sont NULLABLE : la migration est idempotente sur les
-- dossiers existants (les anciens dossiers ne portent pas ces champs ;
-- les regles correspondantes resteront silencieuses tant que l'extraction
-- ne les remplit pas).

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
