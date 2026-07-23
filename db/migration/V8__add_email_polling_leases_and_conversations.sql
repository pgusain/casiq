ALTER TABLE email_polling_config ADD COLUMN last_polled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_polling_config ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_polling_config ADD COLUMN lock_owner VARCHAR(64);
ALTER TABLE email_polling_config ADD COLUMN last_error VARCHAR(1000);
ALTER TABLE email_polling_config ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_email_polling_config_claim
    ON email_polling_config(next_refresh_at, locked_until);

CREATE TABLE work_account_conversation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_account_id UUID NOT NULL REFERENCES work_account(id) ON DELETE CASCADE,
    provider_code VARCHAR(32) NOT NULL REFERENCES email_provider_reference(code),
    provider_message_id VARCHAR(255) NOT NULL,
    provider_thread_id VARCHAR(255),
    subject VARCHAR(998),
    sender VARCHAR(998),
    recipients TEXT,
    sent_at TIMESTAMP WITH TIME ZONE,
    snippet TEXT,
    payload_json TEXT,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_account_conversation_message
        UNIQUE (work_account_id, provider_message_id)
);

CREATE INDEX idx_work_account_conversation_account_sent
    ON work_account_conversation(work_account_id, sent_at);
