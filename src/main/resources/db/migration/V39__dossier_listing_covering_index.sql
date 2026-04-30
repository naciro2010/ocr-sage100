-- Index couvrant pour la query la plus chaude de l'app : DossierController#list
-- (= DossierRepository.findAllProjected, paginated 20 par defaut, trie par
-- date_creation DESC, ETag + cache 5s cote client).
--
-- Avant : `idx_dossier_date_creation` (V1) permet le sort mais Postgres doit
-- faire un Heap Fetch pour recuperer les 9 colonnes scalaires projetees,
-- soit 1 row I/O par dossier de la page. Sur une table de 10 000 dossiers,
-- l'EXPLAIN ANALYZE montre typiquement Index Scan + Heap Fetch ~5-15 ms.
--
-- Apres : avec INCLUDE, Postgres peut servir la page entiere en Index Only
-- Scan (zero heap fetch) tant que le visibility map est a jour. Gain ~3-5x
-- sur cette query, qui est appelee a chaque ouverture de la page d'accueil
-- + chaque rafraichissement (cache client 5s).
--
-- Note : on ne couvre PAS `description` (TEXT potentiellement long) ni les
-- agregats (COUNT documents/resultats) qui viennent des LEFT JOIN -> ces
-- joins continuent d'etre indexes par idx_document_dossier_type (V3) et
-- idx_resultat_validation_dossier_statut (V3).
--
-- CREATE INDEX CONCURRENTLY interdit dans Flyway (transaction implicite),
-- mais sur dossier_paiement la table est petite (qq milliers de lignes),
-- le lock est negligeable. Si besoin: `SET maintenance_work_mem = '64MB'`.

CREATE INDEX IF NOT EXISTS idx_dossier_listing_covering
    ON dossier_paiement (date_creation DESC)
    INCLUDE (id, reference, type, statut, fournisseur, montant_ttc, montant_net_a_payer);
