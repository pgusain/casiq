ALTER TABLE work_account_conversation
    ADD COLUMN work_item_processed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE work_account_conversation
    ADD COLUMN work_item_next_attempt_at TIMESTAMP WITH TIME ZONE
        NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE work_account_conversation
    ADD COLUMN work_item_locked_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE work_account_conversation
    ADD COLUMN work_item_lock_owner VARCHAR(64);

ALTER TABLE work_account_conversation
    ADD COLUMN work_item_last_error VARCHAR(1000);

ALTER TABLE work_account_conversation
    ADD COLUMN work_item_failures INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_conversation_work_item_claim
    ON work_account_conversation(
        direction,
        work_item_processed_at,
        work_item_next_attempt_at,
        work_item_locked_until
    );

-- V6 used an unnamed UNIQUE constraint on work_account_id. Rebuilding these two
-- tables removes it portably in both PostgreSQL and H2 while preserving history.
ALTER TABLE work_item_activity RENAME TO work_item_activity_v10_old;
ALTER TABLE work_item_execution RENAME TO work_item_execution_v10_old;

CREATE TABLE work_item_execution (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_account_id UUID NOT NULL REFERENCES work_account(id) ON DELETE CASCADE,
    work_account_email VARCHAR(320) NOT NULL,
    conversation_id UUID UNIQUE REFERENCES work_account_conversation(id) ON DELETE CASCADE,
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    current_status_id UUID NOT NULL REFERENCES work_item_status(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO work_item_execution (
    id,
    tenant_id,
    work_account_id,
    work_account_email,
    conversation_id,
    definition_id,
    current_status_id,
    created_at,
    updated_at
)
SELECT
    execution.id,
    execution.tenant_id,
    execution.work_account_id,
    account.email_id,
    NULL,
    execution.definition_id,
    execution.current_status_id,
    execution.created_at,
    execution.updated_at
FROM work_item_execution_v10_old execution
JOIN work_account account ON account.id = execution.work_account_id;

CREATE TABLE work_item_activity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    execution_id UUID NOT NULL REFERENCES work_item_execution(id) ON DELETE CASCADE,
    transition_id UUID REFERENCES work_item_status_transition(id) ON DELETE SET NULL,
    performed_by_user_id UUID NOT NULL REFERENCES application_user(id),
    transition_label VARCHAR(160) NOT NULL,
    from_status_code VARCHAR(64) NOT NULL,
    to_status_code VARCHAR(64) NOT NULL,
    performed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO work_item_activity (
    id,
    tenant_id,
    execution_id,
    transition_id,
    performed_by_user_id,
    transition_label,
    from_status_code,
    to_status_code,
    performed_at
)
SELECT
    id,
    tenant_id,
    execution_id,
    transition_id,
    performed_by_user_id,
    transition_label,
    from_status_code,
    to_status_code,
    performed_at
FROM work_item_activity_v10_old;

DROP TABLE work_item_activity_v10_old;
DROP TABLE work_item_execution_v10_old;

CREATE INDEX idx_work_item_execution_tenant ON work_item_execution(tenant_id);
CREATE INDEX idx_work_item_execution_account ON work_item_execution(work_account_id);
CREATE INDEX idx_work_item_execution_definition ON work_item_execution(definition_id);
CREATE INDEX idx_work_item_execution_status ON work_item_execution(current_status_id);
CREATE INDEX idx_work_item_activity_execution ON work_item_activity(execution_id, performed_at);
