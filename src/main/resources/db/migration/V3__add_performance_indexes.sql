-- =====================================================
-- V3: Performance indexes
-- Add missing FK indexes + composite indexes for query optimization
-- =====================================================

-- Foreign key indexes on child tables (critical for JOINs and CASCADE deletes)
CREATE INDEX IF NOT EXISTS idx_facture_dossier ON facture(dossier_id);
CREATE INDEX IF NOT EXISTS idx_ligne_facture_facture ON ligne_facture(facture_id);
CREATE INDEX IF NOT EXISTS idx_bon_commande_dossier ON bon_commande(dossier_id);
CREATE INDEX IF NOT EXISTS idx_contrat_avenant_dossier ON contrat_avenant(dossier_id);
CREATE INDEX IF NOT EXISTS idx_grille_tarifaire_contrat ON grille_tarifaire(contrat_avenant_id);
CREATE INDEX IF NOT EXISTS idx_ordre_paiement_dossier ON ordre_paiement(dossier_id);
CREATE INDEX IF NOT EXISTS idx_retenue_op ON retenue(op_id);
CREATE INDEX IF NOT EXISTS idx_checklist_dossier ON checklist_autocontrole(dossier_id);
CREATE INDEX IF NOT EXISTS idx_point_controle_checklist ON point_controle(checklist_id);
CREATE INDEX IF NOT EXISTS idx_signataire_checklist ON signataire_checklist(checklist_id);
CREATE INDEX IF NOT EXISTS idx_tableau_controle_dossier ON tableau_controle(dossier_id);
CREATE INDEX IF NOT EXISTS idx_point_financier_tableau ON point_controle_financier(tableau_controle_id);
CREATE INDEX IF NOT EXISTS idx_pv_reception_dossier ON pv_reception(dossier_id);
CREATE INDEX IF NOT EXISTS idx_attestation_fiscale_dossier ON attestation_fiscale(dossier_id);

-- Composite indexes for frequently used queries
CREATE INDEX IF NOT EXISTS idx_document_dossier_type ON document(dossier_id, type_document);
CREATE INDEX IF NOT EXISTS idx_resultat_validation_dossier_statut ON resultat_validation(dossier_id, statut);
CREATE INDEX IF NOT EXISTS idx_audit_log_dossier_date ON audit_log(dossier_id, date_action DESC);

-- Composite index for search query optimization
CREATE INDEX IF NOT EXISTS idx_dossier_statut_type ON dossier_paiement(statut, type);
