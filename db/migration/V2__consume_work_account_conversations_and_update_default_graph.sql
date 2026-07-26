ALTER TABLE work_item_execution
    DROP CONSTRAINT fk_work_item_execution_conversation;

ALTER TABLE work_item_execution
    ADD CONSTRAINT fk_work_item_execution_conversation
    FOREIGN KEY (conversation_id)
    REFERENCES work_account_conversation(id) ON DELETE SET NULL;

UPDATE work_item_status_transition
SET to_status_id = 115,
    label = 'Start work'
WHERE id = 130
  AND definition_id = 100;

UPDATE work_item_status_transition
SET to_status_id = 125,
    label = 'Start work'
WHERE id = 140
  AND definition_id = 101;

DELETE FROM work_item_status_transition
WHERE id IN (131, 133, 134, 141, 143, 144);

DELETE FROM work_account_conversation
WHERE work_item_processed_at IS NOT NULL;
