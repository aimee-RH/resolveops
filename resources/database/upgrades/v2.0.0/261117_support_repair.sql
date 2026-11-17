-- Bind a repair to the case version and both triggering events.
ALTER TABLE support_action_proposal
    ADD COLUMN IF NOT EXISTS expected_case_version BIGINT,
    ADD COLUMN IF NOT EXISTS failure_event_id VARCHAR(64) REFERENCES support_subscription_event(id);
CREATE UNIQUE INDEX IF NOT EXISTS support_action_proposal_run ON support_action_proposal(run_id);
CREATE UNIQUE INDEX IF NOT EXISTS support_action_operation_proposal ON support_action_operation(proposal_id);
