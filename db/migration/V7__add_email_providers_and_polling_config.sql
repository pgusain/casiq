CREATE TABLE email_provider_reference (
    code VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

INSERT INTO email_provider_reference (code, display_name, active, sort_order)
VALUES
    ('GOOGLE', 'Google', TRUE, 10),
    ('MICROSOFT', 'Microsoft', TRUE, 20);

ALTER TABLE work_account ADD COLUMN provider_code VARCHAR(32);
UPDATE work_account SET provider_code = 'GOOGLE';
ALTER TABLE work_account ALTER COLUMN provider_code SET NOT NULL;
ALTER TABLE work_account ADD CONSTRAINT fk_work_account_provider
    FOREIGN KEY (provider_code) REFERENCES email_provider_reference(code);

CREATE TABLE email_polling_config (
    id UUID PRIMARY KEY,
    work_account_id UUID NOT NULL UNIQUE REFERENCES work_account(id) ON DELETE CASCADE,
    email_id VARCHAR(320) NOT NULL,
    provider_code VARCHAR(32) NOT NULL REFERENCES email_provider_reference(code),
    access_token TEXT,
    access_token_expires_at TIMESTAMP WITH TIME ZONE,
    next_refresh_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_email_polling_config_next_refresh
    ON email_polling_config(next_refresh_at);

INSERT INTO email_polling_config (
    id,
    work_account_id,
    email_id,
    provider_code,
    access_token,
    access_token_expires_at,
    next_refresh_at,
    created_at,
    updated_at
)
SELECT
    account.id,
    account.id,
    account.email_id,
    'GOOGLE',
    account.access_token,
    account.access_token_expires_at,
    account.access_token_expires_at,
    account.created_at,
    account.updated_at
FROM work_account account;

ALTER TABLE work_account DROP CONSTRAINT ck_work_account_provider;
ALTER TABLE work_account DROP COLUMN connection_provider;
ALTER TABLE work_account DROP COLUMN connected_email_id;
ALTER TABLE work_account DROP COLUMN access_token;
ALTER TABLE work_account DROP COLUMN access_token_expires_at;
ALTER TABLE work_account DROP COLUMN connected_at;
