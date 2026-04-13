-- Indexes on all dossier_id foreign keys for fast lookups and cascade deletes
CREATE INDEX IF NOT EXISTS idx_document_dossier ON document(dossier_id);
CREATE INDEX IF NOT EXISTS idx_facture_dossier ON facture(dossier_id);
CREATE INDEX IF NOT EXISTS idx_facture_document ON facture(document_id);
CREATE INDEX IF NOT EXISTS idx_bon_commande_dossier ON bon_commande(dossier_id);
CREATE INDEX IF NOT EXISTS idx_contrat_avenant_dossier ON contrat_avenant(dossier_id);
CREATE INDEX IF NOT EXISTS idx_ordre_paiement_dossier ON ordre_paiement(dossier_id);
CREATE INDEX IF NOT EXISTS idx_checklist_dossier ON checklist_autocontrole(dossier_id);
CREATE INDEX IF NOT EXISTS idx_tableau_controle_dossier ON tableau_controle(dossier_id);
CREATE INDEX IF NOT EXISTS idx_pv_reception_dossier ON pv_reception(dossier_id);
CREATE INDEX IF NOT EXISTS idx_attestation_fiscale_dossier ON attestation_fiscale(dossier_id);
CREATE INDEX IF NOT EXISTS idx_resultat_validation_dossier ON resultat_validation(dossier_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_dossier ON audit_log(dossier_id);

-- Indexes on child table foreign keys for cascade deletes
CREATE INDEX IF NOT EXISTS idx_ligne_facture_facture ON ligne_facture(facture_id);
CREATE INDEX IF NOT EXISTS idx_grille_tarifaire_contrat ON grille_tarifaire(contrat_avenant_id);
CREATE INDEX IF NOT EXISTS idx_retenue_op ON retenue(op_id);
CREATE INDEX IF NOT EXISTS idx_point_controle_checklist ON point_controle(checklist_id);
CREATE INDEX IF NOT EXISTS idx_signataire_checklist ON signataire_checklist(checklist_id);
