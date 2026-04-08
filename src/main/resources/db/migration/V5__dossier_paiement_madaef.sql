-- =====================================================
-- V5: Plateforme de Reconciliation des Dossiers de Paiement MADAEF
-- =====================================================

-- Dossier de paiement (entite principale)
CREATE TABLE dossier_paiement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(100) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL CHECK (type IN ('BC', 'CONTRACTUEL')),
    statut VARCHAR(30) NOT NULL DEFAULT 'BROUILLON' CHECK (statut IN ('BROUILLON', 'EN_VERIFICATION', 'VALIDE', 'REJETE')),
    fournisseur VARCHAR(500),
    description TEXT,
    montant_ttc NUMERIC(15,2),
    montant_ht NUMERIC(15,2),
    montant_tva NUMERIC(15,2),
    montant_net_a_payer NUMERIC(15,2),
    date_creation TIMESTAMP NOT NULL DEFAULT NOW(),
    date_validation TIMESTAMP,
    valide_par VARCHAR(200),
    motif_rejet TEXT
);

-- Documents PDF sources
CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    type_document VARCHAR(50) NOT NULL CHECK (type_document IN (
        'FACTURE', 'BON_COMMANDE', 'CONTRAT_AVENANT', 'ORDRE_PAIEMENT',
        'CHECKLIST_AUTOCONTROLE', 'CHECKLIST_PIECES', 'TABLEAU_CONTROLE',
        'PV_RECEPTION', 'ATTESTATION_FISCALE', 'FORMULAIRE_FOURNISSEUR'
    )),
    nom_fichier VARCHAR(500) NOT NULL,
    chemin_fichier VARCHAR(1000) NOT NULL,
    texte_extrait TEXT,
    donnees_extraites JSONB,
    statut_extraction VARCHAR(20) NOT NULL DEFAULT 'EN_ATTENTE' CHECK (statut_extraction IN ('EN_ATTENTE', 'EN_COURS', 'EXTRAIT', 'ERREUR')),
    erreur_extraction TEXT,
    date_upload TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_document_dossier ON document(dossier_id);
CREATE INDEX idx_document_type ON document(type_document);

-- Facture
CREATE TABLE facture (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    numero_facture VARCHAR(200),
    date_facture DATE,
    fournisseur VARCHAR(500),
    client VARCHAR(500),
    ice VARCHAR(20),
    identifiant_fiscal VARCHAR(50),
    rc VARCHAR(100),
    rib VARCHAR(50),
    montant_ht NUMERIC(15,2),
    montant_tva NUMERIC(15,2),
    taux_tva NUMERIC(5,2),
    montant_ttc NUMERIC(15,2),
    reference_contrat VARCHAR(200),
    periode VARCHAR(500),
    UNIQUE(dossier_id)
);

-- Lignes de facture
CREATE TABLE ligne_facture (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    facture_id UUID NOT NULL REFERENCES facture(id) ON DELETE CASCADE,
    code_article VARCHAR(100),
    designation TEXT NOT NULL,
    quantite NUMERIC(15,3),
    unite VARCHAR(50),
    prix_unitaire_ht NUMERIC(15,2),
    montant_total_ht NUMERIC(15,2)
);

-- Bon de commande (Type BC)
CREATE TABLE bon_commande (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    reference VARCHAR(200),
    date_bc DATE,
    fournisseur VARCHAR(500),
    objet TEXT,
    montant_ht NUMERIC(15,2),
    montant_tva NUMERIC(15,2),
    taux_tva NUMERIC(5,2),
    montant_ttc NUMERIC(15,2),
    signataire VARCHAR(300),
    UNIQUE(dossier_id)
);

-- Contrat / Avenant (Type Contractuel)
CREATE TABLE contrat_avenant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    reference_contrat VARCHAR(200),
    numero_avenant VARCHAR(100),
    date_signature DATE,
    parties TEXT,
    objet TEXT,
    date_effet DATE,
    UNIQUE(dossier_id)
);

-- Grilles tarifaires du contrat/avenant
CREATE TABLE grille_tarifaire (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contrat_avenant_id UUID NOT NULL REFERENCES contrat_avenant(id) ON DELETE CASCADE,
    designation TEXT NOT NULL,
    prix_unitaire_ht NUMERIC(15,2),
    periodicite VARCHAR(20) CHECK (periodicite IN ('MENSUEL', 'TRIMESTRIEL', 'ANNUEL', 'JOURNALIER')),
    entite VARCHAR(200)
);

-- Ordre de paiement
CREATE TABLE ordre_paiement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    numero_op VARCHAR(100),
    date_emission DATE,
    emetteur VARCHAR(500),
    nature_operation VARCHAR(500),
    description TEXT,
    beneficiaire VARCHAR(500),
    rib VARCHAR(50),
    banque VARCHAR(300),
    montant_operation NUMERIC(15,2),
    reference_facture VARCHAR(200),
    reference_bc_ou_contrat VARCHAR(200),
    reference_sage VARCHAR(200),
    conclusion_controleur TEXT,
    pieces_justificatives TEXT,
    UNIQUE(dossier_id)
);

-- Retenues sur ordre de paiement
CREATE TABLE retenue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    op_id UUID NOT NULL REFERENCES ordre_paiement(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL CHECK (type IN ('TVA_SOURCE', 'IS_HONORAIRES', 'GARANTIE', 'AUTRE')),
    article_cgi VARCHAR(50),
    base NUMERIC(15,2),
    taux NUMERIC(5,2),
    montant NUMERIC(15,2)
);

-- Checklist d'autocontrole
CREATE TABLE checklist_autocontrole (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    reference VARCHAR(100),
    nom_projet VARCHAR(500),
    reference_facture VARCHAR(200),
    prestataire VARCHAR(500),
    UNIQUE(dossier_id)
);

CREATE TABLE point_controle (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id UUID NOT NULL REFERENCES checklist_autocontrole(id) ON DELETE CASCADE,
    numero INT NOT NULL,
    description TEXT,
    est_valide BOOLEAN,
    observation TEXT
);

CREATE TABLE signataire_checklist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id UUID NOT NULL REFERENCES checklist_autocontrole(id) ON DELETE CASCADE,
    nom VARCHAR(300),
    date_signature DATE,
    a_signature BOOLEAN DEFAULT false
);

-- Tableau de controle financier
CREATE TABLE tableau_controle (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    societe_geree VARCHAR(300),
    reference_facture VARCHAR(200),
    fournisseur VARCHAR(500),
    signataire VARCHAR(300),
    UNIQUE(dossier_id)
);

CREATE TABLE point_controle_financier (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tableau_controle_id UUID NOT NULL REFERENCES tableau_controle(id) ON DELETE CASCADE,
    numero INT NOT NULL,
    description TEXT,
    observation VARCHAR(200),
    commentaire TEXT
);

-- PV de reception (Type Contractuel)
CREATE TABLE pv_reception (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    titre VARCHAR(500),
    date_reception DATE,
    reference_contrat VARCHAR(200),
    periode_debut DATE,
    periode_fin DATE,
    prestations TEXT,
    signataire_madaef VARCHAR(300),
    signataire_fournisseur VARCHAR(300),
    UNIQUE(dossier_id)
);

-- Attestation de regularite fiscale (Type Contractuel)
CREATE TABLE attestation_fiscale (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    numero VARCHAR(100),
    date_edition DATE,
    raison_sociale VARCHAR(500),
    identifiant_fiscal VARCHAR(50),
    ice VARCHAR(20),
    rc VARCHAR(100),
    est_en_regle BOOLEAN,
    date_validite DATE,
    UNIQUE(dossier_id)
);

-- Resultats de validation
CREATE TABLE resultat_validation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID NOT NULL REFERENCES dossier_paiement(id) ON DELETE CASCADE,
    regle VARCHAR(20) NOT NULL,
    libelle TEXT NOT NULL,
    statut VARCHAR(20) NOT NULL CHECK (statut IN ('CONFORME', 'NON_CONFORME', 'AVERTISSEMENT', 'NON_APPLICABLE')),
    detail TEXT,
    valeur_attendue TEXT,
    valeur_trouvee TEXT,
    date_execution TIMESTAMP NOT NULL DEFAULT NOW(),
    source VARCHAR(20) NOT NULL DEFAULT 'DETERMINISTE' CHECK (source IN ('DETERMINISTE', 'LLM'))
);
CREATE INDEX idx_validation_dossier ON resultat_validation(dossier_id);
