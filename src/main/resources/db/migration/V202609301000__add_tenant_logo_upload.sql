-- Uploaded tenant logo (ADR 0020). The bytes live in FileStorage under logo_storage_key; the
-- content type is the Tika-detected one, and logo_version (a SHA-256 prefix of the bytes) is the
-- cache-busting query value for the immutable /tenant-logo response. The older free-text
-- logo_url is left in place and is now only a fallback for the email layout.
ALTER TABLE tenant
    ADD COLUMN logo_storage_key  varchar(255),
    ADD COLUMN logo_content_type varchar(50),
    ADD COLUMN logo_version      varchar(16);
