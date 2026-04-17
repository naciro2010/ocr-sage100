-- 1. SHA-256 of every uploaded file. Lets us detect re-uploads of the same PDF
--    and skip the (expensive) OCR + Claude pipeline. Indexed for O(1) lookups.
ALTER TABLE document
    ADD COLUMN IF NOT EXISTS file_hash CHAR(64);

CREATE INDEX IF NOT EXISTS idx_document_file_hash ON document(file_hash);

-- 2. Per-call accounting of Claude API consumption. One row per invocation.
--    Keeps cost visibility per dossier / per document; the admin dashboard
--    aggregates this for monthly billing reconciliation.
CREATE TABLE IF NOT EXISTS claude_usage (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_id  UUID NULL REFERENCES dossier_paiement(id) ON DELETE SET NULL,
    document_id UUID NULL REFERENCES document(id) ON DELETE SET NULL,
    model       VARCHAR(64) NOT NULL,
    input_tokens  INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    success     BOOLEAN NOT NULL DEFAULT true,
    error       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_claude_usage_dossier ON claude_usage(dossier_id);
CREATE INDEX IF NOT EXISTS idx_claude_usage_created ON claude_usage(created_at DESC);
