CREATE TABLE work_item_execution (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_account_id UUID NOT NULL UNIQUE REFERENCES work_account(id) ON DELETE CASCADE,
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    current_status_id UUID NOT NULL REFERENCES work_item_status(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_work_item_execution_tenant ON work_item_execution(tenant_id);
CREATE INDEX idx_work_item_execution_definition ON work_item_execution(definition_id);
CREATE INDEX idx_work_item_execution_status ON work_item_execution(current_status_id);

INSERT INTO work_item_execution (
    id, tenant_id, work_account_id, definition_id, current_status_id
)
SELECT
    account.id,
    account.tenant_id,
    account.id,
    account.work_item_definition_id,
    status.id
FROM work_account account
JOIN work_item_status status
  ON status.definition_id = account.work_item_definition_id
 AND status.initial_status = TRUE;

CREATE TABLE work_item_status_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    status_id UUID NOT NULL REFERENCES work_item_status(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_user(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES application_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_status_assignment UNIQUE (tenant_id, definition_id, status_id, user_id)
);

CREATE INDEX idx_work_item_status_assignment_user ON work_item_status_assignment(user_id);

CREATE TABLE work_item_transition_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    transition_id UUID NOT NULL REFERENCES work_item_status_transition(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_user(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES application_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_transition_assignment UNIQUE (tenant_id, definition_id, transition_id, user_id)
);

CREATE INDEX idx_work_item_transition_assignment_user ON work_item_transition_assignment(user_id);

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

CREATE INDEX idx_work_item_activity_execution ON work_item_activity(execution_id, performed_at);
