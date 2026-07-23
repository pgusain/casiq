ALTER TABLE work_item_execution
    ADD COLUMN work_account_normalized_email VARCHAR(320);

UPDATE work_item_execution
SET work_account_normalized_email = LOWER(work_account_email);

ALTER TABLE work_item_execution
    ALTER COLUMN work_account_normalized_email SET NOT NULL;

CREATE INDEX idx_work_item_execution_queue
    ON work_item_execution(tenant_id, current_status_id, updated_at);

CREATE INDEX idx_work_item_execution_filter
    ON work_item_execution(tenant_id, definition_id, current_status_id);

CREATE INDEX idx_work_item_execution_email
    ON work_item_execution(tenant_id, work_account_normalized_email);

CREATE INDEX idx_work_item_activity_performer
    ON work_item_activity(execution_id, performed_by_user_id);

CREATE INDEX idx_work_item_status_initial
    ON work_item_status(definition_id, initial_status);

CREATE INDEX idx_work_item_status_assignment_lookup
    ON work_item_status_assignment(tenant_id, status_id, user_id);

CREATE INDEX idx_work_item_transition_assignment_lookup
    ON work_item_transition_assignment(tenant_id, user_id, transition_id);
