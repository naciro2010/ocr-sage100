-- Add OCR metadata columns to track OCR engine used and confidence
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS ocr_engine VARCHAR(50);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS ocr_confidence DOUBLE PRECISION;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS ocr_page_count INTEGER;
