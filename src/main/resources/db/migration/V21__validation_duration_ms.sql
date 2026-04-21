-- Stocke la duree d'execution (ms) de chaque regle pour profiler le moteur
-- de validation sans avoir a parser les logs. Nullable : pre-remplissage
-- progressif, les anciennes lignes restent NULL et sont simplement exclues
-- des agregats.
ALTER TABLE resultat_validation
    ADD COLUMN duration_ms BIGINT NULL;

CREATE INDEX idx_resultat_validation_regle_duration
    ON resultat_validation (regle)
    WHERE duration_ms IS NOT NULL;
