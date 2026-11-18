-- Synthetic fixture loaded by scripts/resolveops/seed-demo.sh into a fresh local database.
\getenv operator_password DEMO_OPERATOR_PASSWORD
\getenv approver_password DEMO_APPROVER_PASSWORD
\getenv sop DEMO_SOP
\getenv embedding DEMO_EMBEDDING

INSERT INTO t_user (id, username, password, role, avatar) VALUES
    ('demo-operator', 'demo-operator', :'operator_password', 'user', '/favicon.ico'),
    ('demo-approver', 'demo-approver', :'approver_password', 'user', '/favicon.ico');
INSERT INTO support_tenant VALUES ('demo-acme', 'Acme Demo'), ('demo-other', 'Other Demo');
INSERT INTO support_access_grant VALUES
    ('demo-operator', 'demo-acme', 'OPERATOR'), ('demo-approver', 'demo-acme', 'APPROVER');
INSERT INTO support_subscription VALUES
    ('demo-subscription', 'demo-acme', 'PAID', 'PENDING', 1),
    ('demo-private-sub', 'demo-other', 'PAID', 'ACTIVE', 1);
INSERT INTO support_integration VALUES ('demo-integration', 'demo-acme', true, '{}', 1);
INSERT INTO support_case (id, tenant_id, scenario, resource_id, description) VALUES
    ('demo-activation', 'demo-acme', 'SUBSCRIPTION_ACTIVATION', 'demo-subscription',
     'The customer paid two hours ago. The subscription is still pending after an activation failure.'),
    ('demo-api-403', 'demo-acme', 'API_AUTHORIZATION', 'demo-integration',
     'A team reports that its orders integration recently stopped working.'),
    ('demo-private', 'demo-other', 'SUBSCRIPTION_ACTIVATION', 'demo-private-sub',
     'Private synthetic case in a second tenant.');
INSERT INTO support_subscription_event (id, tenant_id, subscription_id, event_type, occurred_at) VALUES
    ('demo-payment', 'demo-acme', 'demo-subscription', 'PAYMENT_CONFIRMED', now() - interval '2 hours'),
    ('demo-failure', 'demo-acme', 'demo-subscription', 'ACTIVATION_FAILED', now() - interval '1 hour');
INSERT INTO support_api_request_log (id, tenant_id, integration_id, occurred_at, route_template, status_code, error_code)
VALUES ('demo-denial', 'demo-acme', 'demo-integration', now(), '/orders/{id}', 403, 'MISSING_SCOPE');

INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by)
VALUES ('demo-support-sops', 'Demo support SOPs', 'resolveops-local-embedding', 'support-sops', 'demo-operator');
INSERT INTO t_knowledge_document (id, kb_id, doc_name, file_url, file_type, created_by)
VALUES ('demo-sop', 'demo-support-sops', 'Subscription activation reconciliation', 'fixture:demo-sop', 'txt', 'demo-operator');
INSERT INTO t_knowledge_chunk (id, kb_id, doc_id, chunk_index, content, created_by)
VALUES ('demo-sop-1', 'demo-support-sops', 'demo-sop', 0, :'sop', 'demo-operator');
INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding)
VALUES ('demo-sop-1', 'support-sops', :'sop', '{"doc_id":"demo-sop","chunk_index":0}', :'embedding');
INSERT INTO support_runbook_policy (doc_id, content_hash, policy_code)
VALUES ('demo-sop', encode(sha256(convert_to(:'sop', 'UTF8')), 'hex'), 'ACTIVATION_RECONCILE_V1');
