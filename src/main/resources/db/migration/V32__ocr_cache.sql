-- Cache OCR cross-dossier. Cle = SHA-256 du fichier source. Un meme PDF
-- (meme bytes) re-uploade sur un autre dossier ou retraite via
-- processDocument reutilise le texte OCR deja calcule : on evite Tika +
-- Mistral + Tesseract sur le hot path.
--
-- La valeur est le resultat de OcrService.OcrResult (texte normalise +
-- moteur gagnant + pageCount + confidence). cache_version permet d'invalider
-- toutes les entrees en bloc si l'algorithme de selection ou un moteur
-- change materiellement. hit_count / last_hit_at servent a observer le
-- taux de reuse reel sans fouiller les logs.
CREATE TABLE IF NOT EXISTS ocr_cache (
    sha256          CHAR(64)      NOT NULL PRIMARY KEY,
    cache_version   VARCHAR(32)   NOT NULL,
    text            TEXT          NOT NULL,
    engine          VARCHAR(32)   NOT NULL,
    page_count      INTEGER       NOT NULL DEFAULT 1,
    confidence      DOUBLE PRECISION NOT NULL DEFAULT -1,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_hit_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hit_count       INTEGER       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ocr_cache_version ON ocr_cache(cache_version);
CREATE INDEX IF NOT EXISTS idx_ocr_cache_last_hit ON ocr_cache(last_hit_at DESC);
