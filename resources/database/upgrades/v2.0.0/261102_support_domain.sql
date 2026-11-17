-- ResolveOps synthetic SaaS domain. No changes to upstream users or credentials.
CREATE TABLE IF NOT EXISTS support_tenant (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL
);
CREATE TABLE IF NOT EXISTS support_access_grant (
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL REFERENCES support_tenant(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('VIEWER', 'OPERATOR', 'APPROVER')),
    PRIMARY KEY (user_id, tenant_id, role)
);
CREATE TABLE IF NOT EXISTS support_subscription (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES support_tenant(id),
    payment_state VARCHAR(16) NOT NULL CHECK (payment_state IN ('PAID', 'UNPAID', 'UNKNOWN')),
    activation_state VARCHAR(16) NOT NULL CHECK (activation_state IN ('PENDING', 'FAILED', 'ACTIVE')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (tenant_id, id)
);
CREATE TABLE IF NOT EXISTS support_integration (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES support_tenant(id),
    enabled BOOLEAN NOT NULL,
    permission_snapshot JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (tenant_id, id)
);
CREATE TABLE IF NOT EXISTS support_case (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES support_tenant(id),
    scenario VARCHAR(32) NOT NULL CHECK (scenario IN ('SUBSCRIPTION_ACTIVATION', 'API_AUTHORIZATION')),
    resource_id VARCHAR(64) NOT NULL,
    description VARCHAR(8000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'AWAITING_REVIEW', 'RESOLVED')),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE TABLE IF NOT EXISTS support_subscription_event (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    subscription_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('PAYMENT_CONFIRMED', 'ACTIVATION_FAILED', 'ACTIVATION_REPAIRED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    safe_payload JSONB NOT NULL DEFAULT '{}',
    operation_id VARCHAR(64),
    FOREIGN KEY (tenant_id, subscription_id) REFERENCES support_subscription(tenant_id, id),
    UNIQUE (operation_id)
);
CREATE INDEX IF NOT EXISTS support_subscription_event_timeline
    ON support_subscription_event(tenant_id, subscription_id, occurred_at DESC);
CREATE TABLE IF NOT EXISTS support_api_request_log (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    integration_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    route_template VARCHAR(256) NOT NULL,
    status_code INTEGER NOT NULL CHECK (status_code BETWEEN 100 AND 599),
    error_code VARCHAR(64),
    safe_auth_details JSONB NOT NULL DEFAULT '{}',
    FOREIGN KEY (tenant_id, integration_id) REFERENCES support_integration(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS support_api_request_timeline
    ON support_api_request_log(tenant_id, integration_id, occurred_at DESC);
CREATE TABLE IF NOT EXISTS support_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    diagnosis JSONB,
    config_snapshot JSONB NOT NULL DEFAULT '{}',
    request_key VARCHAR(100),
    request_hash VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, case_id) REFERENCES support_case(tenant_id, id),
    UNIQUE (tenant_id, id),
    UNIQUE (actor_id, case_id, request_key)
);
CREATE TABLE IF NOT EXISTS support_evidence (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_ref VARCHAR(256) NOT NULL,
    resource_version VARCHAR(64),
    content_hash VARCHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    snapshot JSONB NOT NULL,
    FOREIGN KEY (tenant_id, run_id) REFERENCES support_run(tenant_id, id),
    UNIQUE (tenant_id, run_id, id)
);
CREATE TABLE IF NOT EXISTS support_run_event (
    run_id VARCHAR(64) NOT NULL REFERENCES support_run(id),
    sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    type VARCHAR(40) NOT NULL,
    safe_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, sequence)
);
CREATE TABLE IF NOT EXISTS support_runbook_policy (
    doc_id VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    policy_code VARCHAR(64) NOT NULL CHECK (policy_code = 'ACTIVATION_RECONCILE_V1'),
    PRIMARY KEY (doc_id, content_hash)
);
CREATE TABLE IF NOT EXISTS support_action_proposal (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(40) NOT NULL CHECK (action_type = 'RECONCILE_ACTIVATION'),
    expected_version BIGINT NOT NULL,
    payment_event_id VARCHAR(64) NOT NULL REFERENCES support_subscription_event(id),
    runbook_evidence_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'STALE')),
    expires_at TIMESTAMPTZ NOT NULL,
    approver_id VARCHAR(64),
    approved_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, run_id) REFERENCES support_run(tenant_id, id),
    FOREIGN KEY (tenant_id, target_id) REFERENCES support_subscription(tenant_id, id),
    FOREIGN KEY (tenant_id, run_id, runbook_evidence_id) REFERENCES support_evidence(tenant_id, run_id, id)
);
CREATE TABLE IF NOT EXISTS support_action_operation (
    id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(64) NOT NULL REFERENCES support_action_proposal(id),
    business_key VARCHAR(256) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'EXECUTING', 'VERIFIED', 'FAILED', 'NEEDS_REVIEW')),
    attempts INTEGER NOT NULL DEFAULT 0,
    result JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS support_audit_event (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES support_tenant(id),
    case_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    operation_id VARCHAR(64),
    actor_id VARCHAR(64) NOT NULL,
    type VARCHAR(40) NOT NULL,
    safe_payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, case_id) REFERENCES support_case(tenant_id, id)
);
