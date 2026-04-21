-- User-defined validation rules evaluated by the LLM against extracted dossier data.
-- Rules are referenced in resultat_validation.regle by their `code` (auto-generated
-- like "CUSTOM-01") so the existing cascade/override/correction plumbing keeps working.
CREATE TABLE IF NOT EXISTS custom_validation_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(20) NOT NULL UNIQUE,
    libelle VARCHAR(200) NOT NULL,
    description TEXT,
    prompt TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    applies_to_bc BOOLEAN NOT NULL DEFAULT true,
    applies_to_contractuel BOOLEAN NOT NULL DEFAULT true,
    document_types TEXT,           -- CSV list of TypeDocument values (empty = all)
    severity VARCHAR(20) NOT NULL DEFAULT 'NON_CONFORME',
    required_fields TEXT,          -- CSV list of fields the user flagged as required
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100)
);

CREATE INDEX idx_custom_rule_enabled ON custom_validation_rule(enabled);

-- Allow the new CUSTOM source on validation results (created by CustomRuleService).
ALTER TABLE resultat_validation DROP CONSTRAINT IF EXISTS resultat_validation_source_check;
ALTER TABLE resultat_validation ADD CONSTRAINT resultat_validation_source_check
    CHECK (source IN ('DETERMINISTE', 'LLM', 'CHECKLIST', 'CUSTOM'));
