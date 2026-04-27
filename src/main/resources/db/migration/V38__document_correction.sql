-- V38 — Corrections humaines au niveau du document extrait.
--
-- Contexte : avant V38, une correction d'un controleur (ex. "le montant TTC
-- vrai est 1234.56 et non 1200") etait stockee uniquement dans la colonne
-- `valeur_trouvee` de la regle correspondante (ResultatValidation). Quand
-- l'operateur cliquait "Sauvegarder & relancer", le moteur relisait
-- `Facture.montantTtc` / `Document.donneesExtraites` (inchanges) et produisait
-- le meme verdict. La correction etait soit perdue (rerun ecrasait la valeur),
-- soit decoreelle de la realite (statut force, mais valeur source toujours
-- fausse). Effet : impossible d'auditer 100% des regles fiabilisees apres
-- correction (CLAUDE.md, OBJECTIF #1).
--
-- V38 introduit une table `document_correction` qui stocke les overrides au
-- niveau (document, champ). Source unique de verite : a chaque execution de
-- regle, ces overrides sont appliques en memoire sur `donneesExtraites` et
-- les entites typees (Facture, BC, OP...) avant l'evaluation. Les regles
-- voient donc systematiquement les valeurs corrigees.
--
-- Audit : chaque correction conserve la valeur originale, l'auteur, la date,
-- la regle d'origine et le motif. ON DELETE CASCADE pour rester coherent
-- avec la suppression d'un document.

CREATE TABLE document_correction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    champ VARCHAR(255) NOT NULL,
    valeur_originale TEXT,
    valeur_corrigee TEXT,
    regle VARCHAR(64),
    motif TEXT,
    corrige_par VARCHAR(255),
    date_correction TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_document_correction_doc_champ UNIQUE (document_id, champ)
);

CREATE INDEX idx_document_correction_document ON document_correction(document_id);
