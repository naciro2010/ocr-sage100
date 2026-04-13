-- Index on user email for auth lookups
CREATE INDEX IF NOT EXISTS idx_app_user_email ON app_user(email);
