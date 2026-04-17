-- Store QR code verification payload captured from the DGI "attestation de
-- regularite fiscale". The QR points to tax.gov.ma with a "code de
-- verification" identical to the one printed under the QR; rule R19 cross-
-- checks the two and surfaces a mismatch if someone tampers with the document.
ALTER TABLE attestation_fiscale
    ADD COLUMN IF NOT EXISTS code_verification VARCHAR(128),
    ADD COLUMN IF NOT EXISTS qr_payload        TEXT,
    ADD COLUMN IF NOT EXISTS qr_code_extrait   VARCHAR(128),
    ADD COLUMN IF NOT EXISTS qr_host           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS qr_scanned_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS qr_scan_error     TEXT;
