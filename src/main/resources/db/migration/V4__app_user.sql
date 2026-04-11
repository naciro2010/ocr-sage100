-- V3: User management for multi-user access
CREATE TABLE IF NOT EXISTS app_user (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nom         VARCHAR(255) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'OPERATEUR'
                CHECK (role IN ('ADMIN', 'CONTROLEUR', 'OPERATEUR')),
    actif       BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_user_email ON app_user(email);
