-- V27 : Ajoute les types de documents contractuels (couche Engagement).
-- Ces documents sont uploades via la nouvelle page /engagements/upload et
-- pilotent la creation automatique d'engagements (Marche, BC cadre, Contrat).
--
-- MARCHE              : contrat de marche public (avec numero AO, CPS, CCAG-T)
-- BON_COMMANDE_CADRE  : BC cadre pluri-annuel (a distinguer du BON_COMMANDE
--                       "operationnel" rattache a une facture unique)
-- CONTRAT_CADRE       : contrat de prestation recurrente (maintenance, abonnement)

ALTER TABLE document DROP CONSTRAINT IF EXISTS document_type_document_check;
ALTER TABLE document ADD CONSTRAINT document_type_document_check CHECK (type_document IN (
    'FACTURE', 'BON_COMMANDE', 'CONTRAT_AVENANT', 'ORDRE_PAIEMENT',
    'CHECKLIST_AUTOCONTROLE', 'CHECKLIST_PIECES', 'TABLEAU_CONTROLE',
    'PV_RECEPTION', 'ATTESTATION_FISCALE', 'FORMULAIRE_FOURNISSEUR',
    'MARCHE', 'BON_COMMANDE_CADRE', 'CONTRAT_CADRE',
    'INCONNU'
));
