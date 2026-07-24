-- Casiq consolidated schema baseline.
-- This migration is intended for a new, empty database.

CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    company_code VARCHAR(64) NOT NULL,
    normalized_company_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_user (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    username VARCHAR(128) NOT NULL,
    normalized_username VARCHAR(128) NOT NULL,
    first_name VARCHAR(128) NOT NULL,
    last_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_application_user_tenant_username
        UNIQUE (tenant_id, normalized_username),
    CONSTRAINT ck_application_user_role
        CHECK (role IN ('GLOBAL_ADMIN', 'ADMIN', 'PROCESSOR', 'BASE_USER'))
);

CREATE TABLE user_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES application_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE email_provider_reference (
    code VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE work_item_definition (
    id UUID PRIMARY KEY,
    owner_tenant_id UUID NOT NULL REFERENCES tenant(id),
    type VARCHAR(64) NOT NULL,
    normalized_type VARCHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    global_scope BOOLEAN NOT NULL,
    overrides_definition_id UUID REFERENCES work_item_definition(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_owner_type
        UNIQUE (owner_tenant_id, normalized_type),
    CONSTRAINT ck_work_item_scope CHECK (
        (global_scope = TRUE AND overrides_definition_id IS NULL)
        OR (global_scope = FALSE AND overrides_definition_id IS NOT NULL)
    )
);

CREATE TABLE work_item_status (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL
        REFERENCES work_item_definition(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    normalized_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    initial_status BOOLEAN NOT NULL DEFAULT FALSE,
    terminal_status BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_work_item_status_code
        UNIQUE (definition_id, normalized_code)
);

CREATE TABLE work_item_status_transition (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL
        REFERENCES work_item_definition(id) ON DELETE CASCADE,
    from_status_id UUID NOT NULL
        REFERENCES work_item_status(id) ON DELETE CASCADE,
    to_status_id UUID NOT NULL
        REFERENCES work_item_status(id) ON DELETE CASCADE,
    label VARCHAR(160) NOT NULL,
    CONSTRAINT uq_work_item_transition
        UNIQUE (definition_id, from_status_id, to_status_id)
);

CREATE TABLE work_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    email_id VARCHAR(320) NOT NULL,
    normalized_email_id VARCHAR(320) NOT NULL,
    work_item VARCHAR(64) NOT NULL,
    work_item_definition_id UUID NOT NULL
        REFERENCES work_item_definition(id),
    provider_code VARCHAR(32) NOT NULL
        REFERENCES email_provider_reference(code),
    refresh_token TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_account_tenant_email
        UNIQUE (tenant_id, normalized_email_id)
);

CREATE TABLE email_polling_config (
    id UUID PRIMARY KEY,
    work_account_id UUID NOT NULL UNIQUE
        REFERENCES work_account(id) ON DELETE CASCADE,
    email_id VARCHAR(320) NOT NULL,
    provider_code VARCHAR(32) NOT NULL
        REFERENCES email_provider_reference(code),
    access_token TEXT,
    access_token_expires_at TIMESTAMP WITH TIME ZONE,
    next_refresh_at TIMESTAMP WITH TIME ZONE,
    last_polled_at TIMESTAMP WITH TIME ZONE,
    locked_until TIMESTAMP WITH TIME ZONE,
    lock_owner VARCHAR(64),
    last_error VARCHAR(1000),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE SEQUENCE work_item_number_sequence START WITH 100000;

-- conversation_id is linked after work_account_conversation is created because
-- the two tables deliberately reference one another.
CREATE TABLE work_item_execution (
    id UUID PRIMARY KEY,
    work_item_number BIGINT NOT NULL
        DEFAULT NEXTVAL('work_item_number_sequence'),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_account_id UUID NOT NULL
        REFERENCES work_account(id) ON DELETE CASCADE,
    work_account_email VARCHAR(320) NOT NULL,
    work_account_normalized_email VARCHAR(320) NOT NULL,
    conversation_id UUID UNIQUE,
    initial_communication_id UUID,
    email_subject VARCHAR(998),
    email_sender VARCHAR(998),
    email_recipients TEXT,
    email_sent_at TIMESTAMP WITH TIME ZONE,
    email_content_html TEXT,
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    current_status_id UUID NOT NULL REFERENCES work_item_status(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_execution_tenant_number
        UNIQUE (tenant_id, work_item_number)
);

CREATE TABLE work_item_status_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    status_id UUID NOT NULL
        REFERENCES work_item_status(id) ON DELETE CASCADE,
    user_id UUID NOT NULL
        REFERENCES application_user(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES application_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_status_assignment
        UNIQUE (tenant_id, definition_id, status_id, user_id)
);

CREATE TABLE work_item_transition_assignment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    definition_id UUID NOT NULL REFERENCES work_item_definition(id),
    transition_id UUID NOT NULL
        REFERENCES work_item_status_transition(id) ON DELETE CASCADE,
    user_id UUID NOT NULL
        REFERENCES application_user(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES application_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_transition_assignment
        UNIQUE (tenant_id, definition_id, transition_id, user_id)
);

CREATE TABLE work_item_activity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    execution_id UUID NOT NULL
        REFERENCES work_item_execution(id) ON DELETE CASCADE,
    transition_id UUID
        REFERENCES work_item_status_transition(id) ON DELETE SET NULL,
    performed_by_user_id UUID NOT NULL REFERENCES application_user(id),
    transition_label VARCHAR(160) NOT NULL,
    from_status_code VARCHAR(64) NOT NULL,
    to_status_code VARCHAR(64) NOT NULL,
    performed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE work_account_conversation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_account_id UUID NOT NULL
        REFERENCES work_account(id) ON DELETE CASCADE,
    provider_code VARCHAR(32) NOT NULL
        REFERENCES email_provider_reference(code),
    provider_message_id VARCHAR(255) NOT NULL,
    provider_thread_id VARCHAR(255),
    rfc_message_id VARCHAR(998),
    in_reply_to VARCHAR(998),
    reference_ids TEXT,
    direction VARCHAR(16) NOT NULL DEFAULT 'INBOUND',
    subject VARCHAR(998),
    sender VARCHAR(998),
    recipients TEXT,
    sent_at TIMESTAMP WITH TIME ZONE,
    snippet TEXT,
    payload_json TEXT,
    content_text TEXT,
    content_html TEXT,
    outbound_request_id UUID,
    work_item_execution_id UUID
        REFERENCES work_item_execution(id) ON DELETE SET NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    work_item_processed_at TIMESTAMP WITH TIME ZONE,
    work_item_next_attempt_at TIMESTAMP WITH TIME ZONE
        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    work_item_locked_until TIMESTAMP WITH TIME ZONE,
    work_item_lock_owner VARCHAR(64),
    work_item_last_error VARCHAR(1000),
    work_item_failures INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_work_account_conversation_message
        UNIQUE (work_account_id, provider_message_id),
    CONSTRAINT uq_conversation_outbound_request
        UNIQUE (outbound_request_id),
    CONSTRAINT ck_work_account_conversation_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

ALTER TABLE work_item_execution
    ADD CONSTRAINT fk_work_item_execution_conversation
    FOREIGN KEY (conversation_id)
    REFERENCES work_account_conversation(id) ON DELETE CASCADE;

CREATE TABLE work_account_conversation_attachment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    conversation_id UUID NOT NULL
        REFERENCES work_account_conversation(id) ON DELETE CASCADE,
    provider_attachment_id VARCHAR(255) NOT NULL,
    filename VARCHAR(2048) NOT NULL,
    content_type VARCHAR(512),
    content_size BIGINT NOT NULL,
    content_data BYTEA,
    storage_provider VARCHAR(16),
    storage_key TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversation_provider_attachment
        UNIQUE (conversation_id, provider_attachment_id),
    CONSTRAINT ck_conversation_attachment_content CHECK (
        content_data IS NOT NULL
        OR (storage_provider IS NOT NULL AND storage_key IS NOT NULL)
    )
);

CREATE TABLE work_item_communication (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    execution_id UUID NOT NULL
        REFERENCES work_item_execution(id) ON DELETE CASCADE,
    work_account_id UUID NOT NULL
        REFERENCES work_account(id) ON DELETE CASCADE,
    provider_code VARCHAR(32) NOT NULL
        REFERENCES email_provider_reference(code),
    provider_message_id VARCHAR(255) NOT NULL,
    provider_thread_id VARCHAR(255),
    rfc_message_id VARCHAR(998),
    in_reply_to VARCHAR(998),
    reference_ids TEXT,
    direction VARCHAR(16) NOT NULL,
    subject VARCHAR(998),
    sender VARCHAR(998),
    recipients TEXT,
    sent_at TIMESTAMP WITH TIME ZONE,
    cached_snippet TEXT,
    cached_content_text TEXT,
    cached_content_html TEXT,
    cache_refreshed_at TIMESTAMP WITH TIME ZONE,
    cache_expires_at TIMESTAMP WITH TIME ZONE,
    outbound_request_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_communication_provider_message
        UNIQUE (execution_id, provider_message_id),
    CONSTRAINT uq_work_item_communication_outbound_request
        UNIQUE (outbound_request_id),
    CONSTRAINT ck_work_item_communication_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

CREATE TABLE work_item_document (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    execution_id UUID NOT NULL
        REFERENCES work_item_execution(id) ON DELETE CASCADE,
    source_attachment_id UUID
        REFERENCES work_account_conversation_attachment(id) ON DELETE SET NULL,
    filename VARCHAR(2048) NOT NULL,
    content_type VARCHAR(512),
    content_size BIGINT NOT NULL,
    content_data BYTEA,
    document_origin VARCHAR(16) NOT NULL DEFAULT 'INBOUND',
    source_conversation_id UUID
        REFERENCES work_account_conversation(id) ON DELETE SET NULL,
    communication_id UUID
        REFERENCES work_item_communication(id) ON DELETE SET NULL,
    uploaded_by_user_id UUID
        REFERENCES application_user(id) ON DELETE SET NULL,
    storage_provider VARCHAR(16),
    storage_key TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_item_source_attachment
        UNIQUE (execution_id, source_attachment_id),
    CONSTRAINT ck_work_item_document_origin
        CHECK (document_origin IN ('INBOUND', 'INTERNAL', 'OUTBOUND')),
    CONSTRAINT ck_work_item_document_content CHECK (
        content_data IS NOT NULL
        OR (storage_provider IS NOT NULL AND storage_key IS NOT NULL)
    )
);

CREATE TABLE work_item_internal_note (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    execution_id UUID NOT NULL
        REFERENCES work_item_execution(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES application_user(id),
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_application_user_tenant
    ON application_user(tenant_id);
CREATE INDEX idx_user_session_user
    ON user_session(user_id);
CREATE INDEX idx_user_session_expiry
    ON user_session(expires_at);
CREATE INDEX idx_work_item_definition_owner
    ON work_item_definition(owner_tenant_id);
CREATE INDEX idx_work_item_definition_override
    ON work_item_definition(overrides_definition_id);
CREATE INDEX idx_work_item_status_initial
    ON work_item_status(definition_id, initial_status);
CREATE INDEX idx_work_account_tenant
    ON work_account(tenant_id);
CREATE INDEX idx_work_account_work_item_definition
    ON work_account(work_item_definition_id);
CREATE INDEX idx_email_polling_config_next_refresh
    ON email_polling_config(next_refresh_at);
CREATE INDEX idx_email_polling_config_claim
    ON email_polling_config(next_refresh_at, locked_until);
CREATE INDEX idx_work_item_execution_tenant
    ON work_item_execution(tenant_id);
CREATE INDEX idx_work_item_execution_account
    ON work_item_execution(work_account_id);
CREATE INDEX idx_work_item_execution_definition
    ON work_item_execution(definition_id);
CREATE INDEX idx_work_item_execution_status
    ON work_item_execution(current_status_id);
CREATE INDEX idx_work_item_execution_queue
    ON work_item_execution(tenant_id, current_status_id, updated_at);
CREATE INDEX idx_work_item_execution_filter
    ON work_item_execution(tenant_id, definition_id, current_status_id);
CREATE INDEX idx_work_item_execution_email
    ON work_item_execution(tenant_id, work_account_normalized_email);
CREATE INDEX idx_work_item_status_assignment_user
    ON work_item_status_assignment(user_id);
CREATE INDEX idx_work_item_status_assignment_lookup
    ON work_item_status_assignment(tenant_id, status_id, user_id);
CREATE INDEX idx_work_item_transition_assignment_user
    ON work_item_transition_assignment(user_id);
CREATE INDEX idx_work_item_transition_assignment_lookup
    ON work_item_transition_assignment(tenant_id, user_id, transition_id);
CREATE INDEX idx_work_item_activity_execution
    ON work_item_activity(execution_id, performed_at);
CREATE INDEX idx_work_item_activity_performer
    ON work_item_activity(execution_id, performed_by_user_id);
CREATE INDEX idx_work_account_conversation_account_sent
    ON work_account_conversation(work_account_id, sent_at);
CREATE INDEX idx_work_account_conversation_thread
    ON work_account_conversation(work_account_id, provider_thread_id, sent_at);
CREATE INDEX idx_conversation_work_item_claim
    ON work_account_conversation(
        direction,
        work_item_processed_at,
        work_item_next_attempt_at,
        work_item_locked_until
    );
CREATE INDEX idx_conversation_work_item_execution
    ON work_account_conversation(work_item_execution_id, sent_at, received_at);
CREATE INDEX idx_conversation_thread_work_item
    ON work_account_conversation(
        work_account_id,
        provider_thread_id,
        work_item_execution_id
    );
CREATE INDEX idx_conversation_attachment_conversation
    ON work_account_conversation_attachment(conversation_id);
CREATE INDEX idx_work_item_communication_timeline
    ON work_item_communication(execution_id, sent_at, created_at);
CREATE INDEX idx_work_item_communication_provider
    ON work_item_communication(work_account_id, provider_message_id);
CREATE INDEX idx_work_item_document_execution
    ON work_item_document(execution_id, created_at);
CREATE INDEX idx_work_item_document_origin
    ON work_item_document(execution_id, document_origin, created_at);
CREATE INDEX idx_work_item_document_communication
    ON work_item_document(communication_id, created_at);
CREATE INDEX idx_work_item_note_execution
    ON work_item_internal_note(execution_id, created_at);

INSERT INTO tenant (
    id,
    company_code,
    normalized_company_code,
    display_name
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '${initial_admin_company_code}',
    LOWER('${initial_admin_company_code}'),
    '${initial_admin_company_code}'
);

INSERT INTO application_user (
    id,
    tenant_id,
    username,
    normalized_username,
    first_name,
    last_name,
    password_hash,
    role,
    must_change_password
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '${initial_admin_username}',
    LOWER('${initial_admin_username}'),
    '${initial_admin_username}',
    '',
    '${initial_admin_password_hash}',
    'GLOBAL_ADMIN',
    TRUE
);

INSERT INTO email_provider_reference (
    code,
    display_name,
    active,
    sort_order
)
VALUES
    ('GOOGLE', 'Google', TRUE, 10),
    ('MICROSOFT', 'Microsoft', TRUE, 20);

INSERT INTO work_item_definition (
    id,
    owner_tenant_id,
    type,
    normalized_type,
    display_name,
    global_scope,
    active
)
VALUES
    (
        '10000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001',
        'INCOME_TAX',
        'income_tax',
        'Income Tax',
        TRUE,
        TRUE
    ),
    (
        '10000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000001',
        'GST',
        'gst',
        'GST',
        TRUE,
        TRUE
    );

INSERT INTO work_item_status (
    id,
    definition_id,
    code,
    normalized_code,
    display_name,
    initial_status,
    terminal_status,
    sort_order
)
VALUES
    ('11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'NEW', 'new', 'New', TRUE, FALSE, 0),
    ('11000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'IN_PROGRESS', 'in_progress', 'In progress', FALSE, FALSE, 1),
    ('11000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'COMPLETED', 'completed', 'Completed', FALSE, TRUE, 2),
    ('12000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'NEW', 'new', 'New', TRUE, FALSE, 0),
    ('12000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'IN_PROGRESS', 'in_progress', 'In progress', FALSE, FALSE, 1),
    ('12000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 'COMPLETED', 'completed', 'Completed', FALSE, TRUE, 2);

INSERT INTO work_item_status_transition (
    id,
    definition_id,
    from_status_id,
    to_status_id,
    label
)
VALUES
    ('13000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'Start'),
    ('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000003', 'Complete'),
    ('14000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000002', 'Start'),
    ('14000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000003', 'Complete');
