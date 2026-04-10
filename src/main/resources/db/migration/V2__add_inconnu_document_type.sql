-- V2: Add INCONNU document type for unclassified documents
ALTER TABLE document DROP CONSTRAINT IF EXISTS document_type_document_check;
ALTER TABLE document ADD CONSTRAINT document_type_document_check CHECK (type_document IN (
    'FACTURE', 'BON_COMMANDE', 'CONTRAT_AVENANT', 'ORDRE_PAIEMENT',
    'CHECKLIST_AUTOCONTROLE', 'CHECKLIST_PIECES', 'TABLEAU_CONTROLE',
    'PV_RECEPTION', 'ATTESTATION_FISCALE', 'FORMULAIRE_FOURNISSEUR',
    'INCONNU'
));
