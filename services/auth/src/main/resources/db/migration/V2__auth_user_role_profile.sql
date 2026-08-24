ALTER TABLE auth_user
    ADD COLUMN IF NOT EXISTS role VARCHAR(16),
    ADD COLUMN IF NOT EXISTS email VARCHAR(128),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(32);

UPDATE auth_user
SET role = 'admin'
WHERE role IS NULL;

ALTER TABLE auth_user
    ALTER COLUMN role SET DEFAULT 'viewer',
    ALTER COLUMN role SET NOT NULL;

ALTER TABLE auth_user
    ADD CONSTRAINT chk_auth_user_role CHECK (role IN ('admin', 'viewer'));

ALTER TABLE auth_user
    ADD CONSTRAINT chk_auth_user_status CHECK (status IN ('active', 'disabled'));
