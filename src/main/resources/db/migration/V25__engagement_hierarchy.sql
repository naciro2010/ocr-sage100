-- =====================================================
-- V25: Hierarchie Engagement (Marche / BC cadre / Contrat)
-- Nouvelle couche superieure regroupant plusieurs dossiers de paiement
-- issus d'un meme engagement juridique (marche public, BC cadre, contrat).
--
-- Strategie JPA: @Inheritance(JOINED) + discriminator `type`.
-- Un dossier de paiement peut etre rattache a un engagement (nullable)
-- ou rester orphelin (retrocompatibilite totale avec l'existant).
-- =====================================================

-- Table de base (champs communs aux 3 types)
CREATE TABLE engagement (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                       VARCHAR(30) NOT NULL CHECK (type IN ('MARCHE', 'BON_COMMANDE', 'CONTRAT')),
    reference                  VARCHAR(200) NOT NULL UNIQUE,
    statut                     VARCHAR(20) NOT NULL DEFAULT 'ACTIF'
                               CHECK (statut IN ('ACTIF', 'CLOTURE', 'SUSPENDU')),
    objet                      TEXT,
    fournisseur                VARCHAR(500),
    montant_ht                 NUMERIC(15,2),
    montant_tva                NUMERIC(15,2),
    taux_tva                   NUMERIC(5,2),
    montant_ttc                NUMERIC(15,2),
    date_document              DATE,
    date_signature             DATE,
    date_notification          DATE,
    fournisseur_canonique_id   UUID REFERENCES fournisseur_canonique(id) ON DELETE SET NULL,
    date_creation              TIMESTAMP NOT NULL DEFAULT NOW(),
    date_modification          TIMESTAMP,
    version                    BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_engagement_type ON engagement(type);
CREATE INDEX idx_engagement_statut ON engagement(statut);
CREATE INDEX idx_engagement_reference ON engagement(reference);
CREATE INDEX idx_engagement_fournisseur ON engagement(fournisseur);
CREATE INDEX idx_engagement_fournisseur_canonique ON engagement(fournisseur_canonique_id);

-- Specifiques Marche public (AO, caution, retenue de garantie, penalites)
CREATE TABLE engagement_marche (
    id                         UUID PRIMARY KEY REFERENCES engagement(id) ON DELETE CASCADE,
    numero_ao                  VARCHAR(100),
    date_ao                    DATE,
    categorie                  VARCHAR(20) CHECK (categorie IN ('TRAVAUX', 'FOURNITURES', 'SERVICES')),
    delai_execution_mois       INT,
    penalites_retard_jour_pct  NUMERIC(6,4),
    retenue_garantie_pct       NUMERIC(5,2),
    caution_definitive_pct     NUMERIC(5,2),
    revision_prix_autorisee    BOOLEAN NOT NULL DEFAULT false
);

-- Specifiques Bon de commande cadre (plafond, anti-fractionnement)
CREATE TABLE engagement_bon_commande (
    id                         UUID PRIMARY KEY REFERENCES engagement(id) ON DELETE CASCADE,
    plafond_montant            NUMERIC(15,2),
    date_validite_fin          DATE,
    seuil_anti_fractionnement  NUMERIC(15,2)
);

-- Specifiques Contrat (periodicite, reconduction, indice de revision)
CREATE TABLE engagement_contrat (
    id                         UUID PRIMARY KEY REFERENCES engagement(id) ON DELETE CASCADE,
    periodicite                VARCHAR(20) CHECK (periodicite IN ('MENSUEL', 'TRIMESTRIEL', 'SEMESTRIEL', 'ANNUEL')),
    date_debut                 DATE,
    date_fin                   DATE,
    reconduction_tacite        BOOLEAN NOT NULL DEFAULT false,
    preavis_resiliation_jours  INT,
    indice_revision            VARCHAR(100)
);

-- Rattachement dossier -> engagement (nullable, pas de regression)
ALTER TABLE dossier_paiement ADD COLUMN engagement_id UUID
    REFERENCES engagement(id) ON DELETE SET NULL;

CREATE INDEX idx_dossier_engagement ON dossier_paiement(engagement_id);
