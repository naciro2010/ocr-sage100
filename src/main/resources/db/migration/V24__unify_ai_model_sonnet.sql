-- Bascule le modele Claude par defaut vers Sonnet 4.6 pour les environnements
-- qui avaient encore claude-opus-4-7 (deprecie du parametre temperature sur
-- les dernieres versions, et trop couteux pour extraction simple).
--
-- Les classification_model / extraction_model / rules_batch_model custom
-- definis explicitement par l'utilisateur sont preserves.
UPDATE app_settings
SET setting_value = 'claude-sonnet-4-6'
WHERE setting_key = 'ai.model'
  AND (setting_value IS NULL OR setting_value = '' OR setting_value = 'claude-opus-4-7');

-- Nettoie les overrides introduits par PR #67 qui forcaient Haiku pour la
-- classification. Le metier a tranche : un seul modele, Sonnet 4.6.
DELETE FROM app_settings
WHERE setting_key IN ('ai.classification_model', 'ai.extraction_model', 'ai.rules_batch_model')
  AND setting_value IN ('claude-haiku-4-5-20251001', 'claude-opus-4-7');
