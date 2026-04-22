-- Prompt caching Anthropic. On ajoute deux compteurs exposes par l'API
-- Anthropic dans `usage` a chaque reponse :
--   - cache_creation_input_tokens : premiere fois qu'un prefixe est mis en
--     cache (facture ~25% plus cher qu'un input token normal, ephemere 5 min).
--   - cache_read_input_tokens : relecture d'un prefixe deja cache (facture
--     ~10% du prix d'un input token). C'est la le gain.
--
-- On les tracke a part pour :
--   1) Mesurer le hit-ratio cache a froid vs chaud (dashboard Consommation IA)
--   2) Corriger le cout estime cote UI (cache_read est 10x moins cher)
--   3) Detecter une regression si les prompts cessent d'etre stables entre
--      appels (cache invalide par un caractere qui bouge = cout x10).
ALTER TABLE claude_usage
    ADD COLUMN IF NOT EXISTS cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0;

ALTER TABLE claude_usage
    ADD COLUMN IF NOT EXISTS cache_read_input_tokens INTEGER NOT NULL DEFAULT 0;
