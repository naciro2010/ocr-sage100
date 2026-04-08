CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id UUID REFERENCES dossier_paiement(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    detail TEXT,
    utilisateur VARCHAR(200),
    date_action TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_dossier ON audit_log(dossier_id);
CREATE INDEX idx_audit_date ON audit_log(date_action DESC);
