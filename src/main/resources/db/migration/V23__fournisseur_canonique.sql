-- Referentiel fournisseurs canonique : unifie les variantes de noms du meme
-- fournisseur ("Maymana Patisse" / "Maymana Patisserie" / "MAYMANA" -> un
-- seul canonical). Permet a R21 anti-doublon et R14 coherence nom de ne plus
-- etre trompes par des differences orthographiques.

CREATE TABLE fournisseur_canonique (
    id UUID PRIMARY KEY,
    nom_canonique TEXT NOT NULL,
    nom_normalise TEXT NOT NULL,
    source_type_document TEXT NOT NULL,
    ice TEXT NULL,
    identifiant_fiscal TEXT NULL,
    rib TEXT NULL,
    date_creation TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    date_mise_a_jour TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    manuellement_confirme BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_fc_nom_normalise ON fournisseur_canonique (nom_normalise);
CREATE INDEX idx_fc_ice ON fournisseur_canonique (ice) WHERE ice IS NOT NULL;

CREATE TABLE fournisseur_alias (
    id UUID PRIMARY KEY,
    canonique_id UUID NOT NULL REFERENCES fournisseur_canonique(id) ON DELETE CASCADE,
    nom_brut TEXT NOT NULL,
    nom_normalise TEXT NOT NULL,
    source_type_document TEXT NOT NULL,
    similarity_score NUMERIC(4,3) NULL,
    requires_review BOOLEAN NOT NULL DEFAULT false,
    date_creation TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_fa_canonique_id ON fournisseur_alias (canonique_id);
CREATE INDEX idx_fa_nom_normalise ON fournisseur_alias (nom_normalise);
CREATE INDEX idx_fa_review ON fournisseur_alias (requires_review) WHERE requires_review = true;

-- Chaque facture / BC / autre entite produit un nom de fournisseur. On stocke
-- le lien vers le canonical pour que les regles (R14, R21, agregats) travaillent
-- sur l'identite unifiee sans casser le nom original affiche.
ALTER TABLE facture ADD COLUMN fournisseur_canonique_id UUID NULL
    REFERENCES fournisseur_canonique(id) ON DELETE SET NULL;
ALTER TABLE bon_commande ADD COLUMN fournisseur_canonique_id UUID NULL
    REFERENCES fournisseur_canonique(id) ON DELETE SET NULL;
ALTER TABLE contrat_avenant ADD COLUMN fournisseur_canonique_id UUID NULL
    REFERENCES fournisseur_canonique(id) ON DELETE SET NULL;
ALTER TABLE attestation_fiscale ADD COLUMN fournisseur_canonique_id UUID NULL
    REFERENCES fournisseur_canonique(id) ON DELETE SET NULL;

CREATE INDEX idx_facture_canonique ON facture (fournisseur_canonique_id)
    WHERE fournisseur_canonique_id IS NOT NULL;
CREATE INDEX idx_bc_canonique ON bon_commande (fournisseur_canonique_id)
    WHERE fournisseur_canonique_id IS NOT NULL;
