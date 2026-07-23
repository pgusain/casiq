CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    company_code VARCHAR(64) NOT NULL,
    normalized_company_code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_user (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    username VARCHAR(128) NOT NULL,
    normalized_username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_application_user_tenant_username UNIQUE (tenant_id, normalized_username),
    CONSTRAINT ck_application_user_role CHECK (role IN ('GLOBAL_ADMIN', 'ADMIN', 'PROCESSOR', 'BASE_USER'))
);

CREATE INDEX idx_application_user_tenant ON application_user(tenant_id);

CREATE TABLE user_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES application_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_session_user ON user_session(user_id);
CREATE INDEX idx_user_session_expiry ON user_session(expires_at);

INSERT INTO tenant (id, company_code, normalized_company_code, display_name)
SELECT
    '00000000-0000-0000-0000-000000000001',
    '${initial_admin_company_code}',
    LOWER('${initial_admin_company_code}'),
    '${initial_admin_company_code}'
WHERE NOT EXISTS (
    SELECT 1 FROM tenant
    WHERE normalized_company_code = LOWER('${initial_admin_company_code}')
);

INSERT INTO application_user (
    id,
    tenant_id,
    username,
    normalized_username,
    password_hash,
    role,
    must_change_password
)
SELECT
    '00000000-0000-0000-0000-000000000001',
    tenant.id,
    '${initial_admin_username}',
    LOWER('${initial_admin_username}'),
    '${initial_admin_password_hash}',
    'GLOBAL_ADMIN',
    TRUE
FROM tenant
WHERE normalized_company_code = LOWER('${initial_admin_company_code}')
  AND NOT EXISTS (
      SELECT 1
      FROM application_user existing_user
      WHERE existing_user.tenant_id = tenant.id
        AND existing_user.normalized_username = LOWER('${initial_admin_username}')
  );
