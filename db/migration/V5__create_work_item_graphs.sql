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
    CONSTRAINT uq_work_item_owner_type UNIQUE (owner_tenant_id, normalized_type),
    CONSTRAINT ck_work_item_scope CHECK (
        (global_scope = TRUE AND overrides_definition_id IS NULL)
        OR (global_scope = FALSE AND overrides_definition_id IS NOT NULL)
    )
);

CREATE INDEX idx_work_item_definition_owner ON work_item_definition(owner_tenant_id);
CREATE INDEX idx_work_item_definition_override ON work_item_definition(overrides_definition_id);

CREATE TABLE work_item_status (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES work_item_definition(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    normalized_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    initial_status BOOLEAN NOT NULL DEFAULT FALSE,
    terminal_status BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_work_item_status_code UNIQUE (definition_id, normalized_code)
);

CREATE TABLE work_item_status_transition (
    id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES work_item_definition(id) ON DELETE CASCADE,
    from_status_id UUID NOT NULL REFERENCES work_item_status(id) ON DELETE CASCADE,
    to_status_id UUID NOT NULL REFERENCES work_item_status(id) ON DELETE CASCADE,
    label VARCHAR(160) NOT NULL,
    CONSTRAINT uq_work_item_transition UNIQUE (definition_id, from_status_id, to_status_id)
);

INSERT INTO work_item_definition (id, owner_tenant_id, type, normalized_type, display_name, global_scope, active)
VALUES
('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'INCOME_TAX', 'income_tax', 'Income Tax', TRUE, TRUE),
('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'GST', 'gst', 'GST', TRUE, TRUE);

INSERT INTO work_item_status (id, definition_id, code, normalized_code, display_name, initial_status, terminal_status, sort_order)
VALUES
('11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'NEW', 'new', 'New', TRUE, FALSE, 0),
('11000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'IN_PROGRESS', 'in_progress', 'In progress', FALSE, FALSE, 1),
('11000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'COMPLETED', 'completed', 'Completed', FALSE, TRUE, 2),
('12000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'NEW', 'new', 'New', TRUE, FALSE, 0),
('12000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'IN_PROGRESS', 'in_progress', 'In progress', FALSE, FALSE, 1),
('12000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 'COMPLETED', 'completed', 'Completed', FALSE, TRUE, 2);

INSERT INTO work_item_status_transition (id, definition_id, from_status_id, to_status_id, label)
VALUES
('13000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', 'Start'),
('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000003', 'Complete'),
('14000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000001', '12000000-0000-0000-0000-000000000002', 'Start'),
('14000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000002', '12000000-0000-0000-0000-000000000003', 'Complete');

ALTER TABLE work_account DROP CONSTRAINT ck_work_account_work_item;
ALTER TABLE work_account ADD COLUMN work_item_definition_id UUID;
UPDATE work_account SET work_item_definition_id = CASE work_item
    WHEN 'INCOME_TAX' THEN CAST('10000000-0000-0000-0000-000000000001' AS UUID)
    WHEN 'GST' THEN CAST('10000000-0000-0000-0000-000000000002' AS UUID)
END;
ALTER TABLE work_account ALTER COLUMN work_item_definition_id SET NOT NULL;
ALTER TABLE work_account ADD CONSTRAINT fk_work_account_work_item_definition
    FOREIGN KEY (work_item_definition_id) REFERENCES work_item_definition(id);
CREATE INDEX idx_work_account_work_item_definition ON work_account(work_item_definition_id);
