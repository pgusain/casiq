ALTER TABLE work_account_conversation
    ADD COLUMN rfc_message_id VARCHAR(998);

ALTER TABLE work_account_conversation
    ADD COLUMN in_reply_to VARCHAR(998);

ALTER TABLE work_account_conversation
    ADD COLUMN reference_ids TEXT;

ALTER TABLE work_account_conversation
    ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'INBOUND';

ALTER TABLE work_account_conversation
    ADD CONSTRAINT ck_work_account_conversation_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'));

CREATE INDEX idx_work_account_conversation_thread
    ON work_account_conversation(work_account_id, provider_thread_id, sent_at);
