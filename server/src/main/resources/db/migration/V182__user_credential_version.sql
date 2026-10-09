ALTER TABLE app_user ADD COLUMN credential_version bigint NOT NULL DEFAULT 0 CHECK (credential_version >= 0);
