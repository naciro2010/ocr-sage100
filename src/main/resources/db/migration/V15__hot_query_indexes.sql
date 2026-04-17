-- Indexes for queries that hit the hot path under load.
-- Idempotent; safe to re-run.

-- Background processor scans documents by extraction status; without an index
-- this is a sequential scan over every document ever uploaded.
CREATE INDEX IF NOT EXISTS idx_document_statut_extraction
    ON document(statut_extraction);

-- rerunRule + cascade evaluations filter resultat_validation by (dossier, regle).
CREATE INDEX IF NOT EXISTS idx_resultat_validation_dossier_regle
    ON resultat_validation(dossier_id, regle);

-- Override lookup is keyed by (dossier_id, regle) — already UNIQUE, but add an
-- explicit btree to make the cache-miss path consistent.
CREATE INDEX IF NOT EXISTS idx_dossier_rule_override_dossier_regle
    ON dossier_rule_override(dossier_id, regle);

-- Audit log queries always order by date desc.
CREATE INDEX IF NOT EXISTS idx_audit_log_date_action
    ON audit_log(date_action DESC);
