-- Probleme observe en prod : claude_usage_dossier_id_fkey / document_id_fkey
-- violations lors de l'insert, quand le MDC contient un UUID de dossier /
-- document qui n'existe pas (ou plus) en DB. Cascade : la transaction parent
-- est marquee rollback-only et l'extraction echoue completement.
--
-- claude_usage est une table de telemetrie best-effort. Les FK n'apportent rien
-- ici : ON DELETE SET NULL suffisait deja, et les violations bloquent des
-- inserts qui devraient juste etre orphelins (le dashboard admin les filtre
-- par LEFT JOIN de toute facon).
ALTER TABLE claude_usage DROP CONSTRAINT IF EXISTS claude_usage_dossier_id_fkey;
ALTER TABLE claude_usage DROP CONSTRAINT IF EXISTS claude_usage_document_id_fkey;
