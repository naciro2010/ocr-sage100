-- =====================================================
-- V29: Liste personnalisee des pieces a verifier pour la completude (R20)
--
-- Par defaut R20 utilise une liste figee selon le type de dossier (BC ou
-- CONTRACTUEL). Cette colonne permet au controleur de surcharger la liste
-- requise au niveau du dossier : pieces optionnelles, cas particuliers, etc.
--
-- Format: chaine CSV de TypeDocument (ex: "FACTURE,BON_COMMANDE,ORDRE_PAIEMENT").
-- NULL = comportement par defaut (liste figee cote code).
--
-- Note : ce script a ete renumerote de V25 a V29 pour lever le conflit de
-- version Flyway avec V25__engagement_hierarchy, introduit en parallele dans
-- une autre PR. "ADD COLUMN IF NOT EXISTS" reste idempotent si la colonne a
-- deja ete creee par un ancien deploiement.
-- =====================================================

ALTER TABLE dossier_paiement
    ADD COLUMN IF NOT EXISTS required_documents TEXT;
