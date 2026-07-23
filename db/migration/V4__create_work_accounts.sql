CREATE TABLE work_account (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    email_id VARCHAR(320) NOT NULL,
    normalized_email_id VARCHAR(320) NOT NULL,
    work_item VARCHAR(32) NOT NULL,
    connection_provider VARCHAR(32),
    connected_email_id VARCHAR(320),
    access_token TEXT,
    refresh_token TEXT,
    access_token_expires_at TIMESTAMP WITH TIME ZONE,
    connected_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_account_tenant_email UNIQUE (tenant_id, normalized_email_id),
    CONSTRAINT ck_work_account_work_item CHECK (work_item IN ('INCOME_TAX', 'GST')),
    CONSTRAINT ck_work_account_provider CHECK (connection_provider IS NULL OR connection_provider IN ('GMAIL'))
);

CREATE INDEX idx_work_account_tenant ON work_account(tenant_id);
