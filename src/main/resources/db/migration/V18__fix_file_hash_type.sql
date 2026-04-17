-- Fix column type mismatch: CHAR(64) -> VARCHAR(64) to match JPA entity mapping
ALTER TABLE document ALTER COLUMN file_hash TYPE VARCHAR(64);
