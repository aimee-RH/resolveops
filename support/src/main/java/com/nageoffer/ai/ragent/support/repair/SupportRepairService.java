/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.support.repair;

import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportJson;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nageoffer.ai.ragent.support.SupportException.Code.*;
import static com.nageoffer.ai.ragent.support.access.SupportAccessService.Permission.*;

@Service
public class SupportRepairService {
    private static final String ACTION = "RECONCILE_ACTIVATION";

    public record Receipt(String operationId, String eventId, long subscriptionVersion, String activationState) { }
    public record ProposalView(String id, String targetId, long expectedVersion, String paymentEventId,
                               String failureEventId, String runbookEvidenceId, String expiresAt, String payloadHash,
                               String status, String approverId, String approvedAt, Receipt receipt) { }
    public record RepairView(ProposalView proposal, boolean canPropose, boolean canReview, boolean canExecute) { }

    private record Context(SupportCase supportCase, SupportRunStore.RunView run, String actor) { }
    private record Basis(long expectedVersion, long caseVersion, String payment, String failure, String runbook) { }
    private record Proposal(String id, String target, Basis basis, OffsetDateTime expiresAt,
                            String hash, String status, String approver, OffsetDateTime approvedAt) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final SupportAccessService access;
    private final SupportRunStore runs;

    public SupportRepairService(NamedParameterJdbcTemplate jdbc, SupportAccessService access, SupportRunStore runs) {
        this.jdbc = jdbc;
        this.access = access;
        this.runs = runs;
    }

    public RepairView get(String actor, String runId) {
        return view(context(actor, runId, READ, false));
    }

    @Transactional
    public RepairView propose(String actor, String runId) {
        var context = context(actor, runId, INVESTIGATE, true);
        requireRequester(context);
        if (proposal(runId) != null) return view(context);

        var diagnosis = context.run().diagnosis();
        if (!suggestsRepair(context)) throw error(CONFLICT);
        var observed = runs.evidence(actor, runId);
        Set<String> citations = citations(diagnosis);
        String target = context.supportCase().resourceId();
        var subscription = observed.stream().filter(e -> isCited(e, citations, "SUBSCRIPTION", target))
                .filter(e -> "PAID".equals(e.snapshot().get("paymentState"))
                        && ("PENDING".equals(e.snapshot().get("activationState")) || "FAILED".equals(e.snapshot().get("activationState"))))
                .reduce((first, last) -> last).orElseThrow(() -> error(REPAIR_STALE));
        var timeline = observed.stream().filter(e -> isCited(e, citations, "EVENTS", target))
                .filter(e -> Boolean.FALSE.equals(e.snapshot().get("truncated")))
                .reduce((first, last) -> last).orElseThrow(() -> error(REPAIR_STALE));
        long version = observedVersion(subscription);
        String payment = eventId(timeline, "PAYMENT_CONFIRMED");
        String failure = eventId(timeline, "ACTIVATION_FAILED");
        var runbook = observed.stream().filter(e -> "RUNBOOK".equals(e.kind())
                        && diagnosis.get("runbookEvidenceIds") instanceof List<?> ids && ids.contains(e.id()))
                .filter(e -> registeredRunbook(e, false)).findFirst().orElseThrow(() -> error(REPAIR_STALE));
        var basis = new Basis(version, context.supportCase().version(), payment, failure, runbook.id());
        validateLive(context, basis);
        if (!registeredRunbook(runbook, true)) throw error(REPAIR_STALE);
        var expiresAt = jdbc.queryForObject("SELECT clock_timestamp() + INTERVAL '15 minutes'", Map.of(), OffsetDateTime.class);
        String id = id();
        String hash = hash(context, id, basis, expiresAt, runbook);
        jdbc.update("""
                INSERT INTO support_action_proposal (id,tenant_id,run_id,target_id,action_type,expected_version,
                  expected_case_version,payment_event_id,failure_event_id,runbook_evidence_id,payload_hash,status,expires_at)
                VALUES (:id,:tenant,:run,:target,'RECONCILE_ACTIVATION',:version,:caseVersion,:payment,:failure,:runbook,
                  :hash,'PENDING_APPROVAL',:expires)
                """, Map.ofEntries(Map.entry("id", id), Map.entry("tenant", context.supportCase().tenantId()),
                Map.entry("run", runId), Map.entry("target", target), Map.entry("version", version),
                Map.entry("caseVersion", basis.caseVersion()), Map.entry("payment", payment), Map.entry("failure", failure),
                Map.entry("runbook", runbook.id()), Map.entry("hash", hash), Map.entry("expires", expiresAt)));
        audit(context, id, null, "REPAIR_PROPOSED", hash);
        return view(context);
    }

    @Transactional
    public RepairView review(String actor, String runId, String payloadHash, boolean approve) {
        var context = context(actor, runId, APPROVE, true);
        if (actor.equals(context.run().actorId())) throw error(NOT_FOUND_OR_FORBIDDEN);
        var proposal = requireBoundProposal(context, payloadHash);
        String decision = approve ? "APPROVED" : "REJECTED";
        if (decision.equals(proposal.status()) && actor.equals(proposal.approver())) return view(context);
        if (!"PENDING_APPROVAL".equals(proposal.status())) throw error(CONFLICT);
        requireUnexpired(proposal);
        if (approve) {
            access.lockGrant(context.run().actorId(), context.supportCase().tenantId(), INVESTIGATE);
            requireCurrentProposal(context, proposal);
        }
        if (jdbc.update("""
                UPDATE support_action_proposal SET status=:status, approver_id=:actor,
                  approved_at=CASE WHEN :status='APPROVED' THEN clock_timestamp() END
                WHERE id=:id AND expires_at>clock_timestamp()
                """, Map.of("status", decision, "actor", actor, "id", proposal.id())) != 1) throw error(REPAIR_EXPIRED);
        audit(context, proposal.id(), null, "REPAIR_" + decision, proposal.hash());
        return view(context);
    }

    @Transactional
    public RepairView execute(String actor, String runId, String payloadHash) {
        var context = context(actor, runId, INVESTIGATE, true);
        requireRequester(context);
        var proposal = requireBoundProposal(context, payloadHash);
        if (!"APPROVED".equals(proposal.status()) || proposal.approvedAt() == null || actor.equals(proposal.approver()))
            throw error(CONFLICT);
        // A committed receipt also answers retries after deadline or version changes. No new write is needed.
        if (receipt(proposal) != null) return view(context);

        requireUnexpired(proposal);
        access.lockGrant(proposal.approver(), context.supportCase().tenantId(), APPROVE);
        requireCurrentProposal(context, proposal);
        requireUnexpired(proposal);

        String operationId = id();
        String eventId = id();
        var basis = proposal.basis();
        var resource = Map.of("tenant", context.supportCase().tenantId(), "target", proposal.target(),
                "version", basis.expectedVersion(), "proposal", proposal.id());
        String key = SupportJson.hash(SupportJson.write(List.of(ACTION, resource.get("tenant"), proposal.target(), basis.expectedVersion())));
        if (jdbc.update("""
                INSERT INTO support_action_operation (id,proposal_id,business_key,request_hash,status,attempts)
                VALUES (:id,:proposal,:key,:hash,'EXECUTING',1) ON CONFLICT DO NOTHING
                """, Map.of("id", operationId, "proposal", proposal.id(), "key", key, "hash", proposal.hash())) != 1)
            throw error(REPAIR_STALE);

        if (jdbc.update("""
                UPDATE support_subscription SET activation_state='ACTIVE',version=version+1
                WHERE tenant_id=:tenant AND id=:target AND version=:version
                AND payment_state='PAID' AND activation_state IN ('PENDING','FAILED')
                AND EXISTS (SELECT 1 FROM support_action_proposal WHERE id=:proposal AND expires_at>clock_timestamp())
                """, resource) != 1) throw error(REPAIR_EXPIRED);
        jdbc.update("""
                INSERT INTO support_subscription_event (id,tenant_id,subscription_id,event_type,occurred_at,operation_id)
                VALUES (:event,:tenant,:target,'ACTIVATION_REPAIRED',clock_timestamp(),:operation)
                """, Map.of("event", eventId, "tenant", resource.get("tenant"), "target", proposal.target(), "operation", operationId));
        long newVersion = basis.expectedVersion() + 1;
        Boolean verified = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM support_subscription s JOIN support_subscription_event e
                  ON e.tenant_id=s.tenant_id AND e.subscription_id=s.id
                  WHERE s.tenant_id=:tenant AND s.id=:target AND s.activation_state='ACTIVE'
                  AND s.payment_state='PAID' AND s.version=:version AND e.id=:event AND e.operation_id=:operation
                  AND e.event_type='ACTIVATION_REPAIRED')
                """, Map.of("tenant", resource.get("tenant"), "target", proposal.target(), "version", newVersion,
                "event", eventId, "operation", operationId), Boolean.class);
        if (!Boolean.TRUE.equals(verified)) throw error(REPAIR_STALE);
        jdbc.update("""
                UPDATE support_action_operation SET status='VERIFIED',result=CAST(:result AS jsonb),updated_at=clock_timestamp()
                WHERE id=:id
                """, Map.of("id", operationId, "result", SupportJson.write(new Receipt(operationId, eventId, newVersion, "ACTIVE"))));
        audit(context, proposal.id(), operationId, "REPAIR_VERIFIED", proposal.hash());
        return view(context);
    }

    private Context context(String actor, String runId, SupportAccessService.Permission permission, boolean lock) {
        SupportAccessService.requireActor(actor);
        if (runId == null || runId.isBlank() || runId.length() > 64) throw error(INVALID_ARGUMENT);
        var run = runs.get(actor, runId);
        var supportCase = lock ? access.lockCase(actor, run.caseId(), permission) : access.requireCase(actor, run.caseId(), permission);
        if (supportCase.scenario() != SupportCase.Scenario.SUBSCRIPTION_ACTIVATION) throw error(NOT_FOUND_OR_FORBIDDEN);
        if (lock) {
            // A run lock serializes proposal creation, decisions and retries for the same run.
            jdbc.queryForObject("SELECT id FROM support_run WHERE id=:id FOR UPDATE", Map.of("id", runId), String.class);
            run = runs.get(actor, runId);
        }
        return new Context(supportCase, run, actor);
    }

    private static void requireRequester(Context context) {
        if (!context.run().actorId().equals(context.actor())) throw error(NOT_FOUND_OR_FORBIDDEN);
    }

    private static boolean suggestsRepair(Context context) {
        return "COMPLETED".equals(context.run().status()) && "PROPOSE_REPAIR".equals(context.run().diagnosis().get("outcome"))
                && Map.of("type", ACTION).equals(context.run().diagnosis().get("recommendedAction"));
    }

    private RepairView view(Context context) {
        var proposal = proposal(context.run().id());
        boolean requester = context.actor().equals(context.run().actorId())
                && access.hasGrant(context.actor(), context.supportCase().tenantId(), INVESTIGATE);
        if (proposal == null) return new RepairView(null, requester && suggestsRepair(context), false, false);

        var receipt = receipt(proposal);
        String status = proposal.status();
        if (receipt != null) status = "VERIFIED";
        else if (Set.of("PENDING_APPROVAL", "APPROVED").contains(status) && expired(proposal)) status = "EXPIRED";
        boolean reviewer = "PENDING_APPROVAL".equals(status) && !context.actor().equals(context.run().actorId())
                && access.hasGrant(context.actor(), context.supportCase().tenantId(), APPROVE);
        var result = new ProposalView(proposal.id(), proposal.target(), proposal.basis().expectedVersion(), proposal.basis().payment(),
                proposal.basis().failure(), proposal.basis().runbook(), proposal.expiresAt().toInstant().toString(), proposal.hash(), status,
                proposal.approver(), proposal.approvedAt() == null ? null : proposal.approvedAt().toInstant().toString(), receipt);
        return new RepairView(result, false, reviewer, requester && "APPROVED".equals(status));
    }

    private Proposal proposal(String runId) {
        return jdbc.query("SELECT * FROM support_action_proposal WHERE run_id=:run", Map.of("run", runId),
                (rs, row) -> readProposal(rs)).stream().findFirst().orElse(null);
    }

    private static Proposal readProposal(ResultSet rs) throws SQLException {
        return new Proposal(rs.getString("id"), rs.getString("target_id"),
                new Basis(rs.getLong("expected_version"), rs.getLong("expected_case_version"),
                        rs.getString("payment_event_id"), rs.getString("failure_event_id"), rs.getString("runbook_evidence_id")),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("payload_hash"), rs.getString("status"),
                rs.getString("approver_id"), rs.getObject("approved_at", OffsetDateTime.class));
    }

    private Receipt receipt(Proposal proposal) {
        return jdbc.query("""
                SELECT id,result FROM support_action_operation
                WHERE proposal_id=:proposal AND request_hash=:hash AND status='VERIFIED'
                """, Map.of("proposal", proposal.id(), "hash", proposal.hash()), (rs, row) -> {
            var result = SupportJson.read(rs.getString("result"));
            return new Receipt(rs.getString("id"), (String) result.get("eventId"),
                    ((Number) result.get("subscriptionVersion")).longValue(), (String) result.get("activationState"));
        }).stream().findFirst().orElse(null);
    }

    private Proposal requireBoundProposal(Context context, String payloadHash) {
        if (payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")) throw error(INVALID_ARGUMENT);
        var proposal = proposal(context.run().id());
        if (proposal == null) throw error(NOT_FOUND_OR_FORBIDDEN);
        if (!proposal.hash().equals(payloadHash)) throw error(REPAIR_STALE);
        return proposal;
    }

    private void requireCurrentProposal(Context context, Proposal proposal) {
        if (!suggestsRepair(context) || proposal.basis().failure() == null
                || !proposal.target().equals(context.supportCase().resourceId())) throw error(REPAIR_STALE);
        var runbook = runs.evidence(context.actor(), context.run().id(), proposal.basis().runbook());
        if (!hash(context, proposal.id(), proposal.basis(), proposal.expiresAt(), runbook).equals(proposal.hash()))
            throw error(REPAIR_STALE);
        validateLive(context, proposal.basis());
        if (!registeredRunbook(runbook, true)) throw error(REPAIR_STALE);
    }

    private void validateLive(Context context, Basis basis) {
        var c = context.supportCase();
        if (c.version() != basis.caseVersion() || "RESOLVED".equals(c.status())) throw error(REPAIR_STALE);
        var subscriptions = jdbc.queryForList("""
                SELECT id FROM support_subscription WHERE tenant_id=:tenant AND id=:target AND version=:version
                AND payment_state='PAID' AND activation_state IN ('PENDING','FAILED') FOR UPDATE
                """, Map.of("tenant", c.tenantId(), "target", c.resourceId(), "version", basis.expectedVersion()), String.class);
        if (subscriptions.isEmpty()) throw error(REPAIR_STALE);
        var events = jdbc.queryForList("""
                SELECT f.id FROM support_subscription_event f JOIN support_subscription_event p
                  ON p.tenant_id=f.tenant_id AND p.subscription_id=f.subscription_id
                WHERE p.id=:payment AND p.event_type='PAYMENT_CONFIRMED' AND f.id=:failure
                  AND f.event_type='ACTIVATION_FAILED' AND f.tenant_id=:tenant AND f.subscription_id=:target
                  AND p.occurred_at<=f.occurred_at FOR SHARE OF f,p
                """, Map.of("tenant", c.tenantId(), "target", c.resourceId(), "payment", basis.payment(), "failure", basis.failure()),
                String.class);
        if (events.isEmpty()) throw error(REPAIR_STALE);
    }

    private boolean registeredRunbook(SupportRunStore.Evidence evidence, boolean lock) {
        if (!"RUNBOOK".equals(evidence.kind()) || !Boolean.FALSE.equals(evidence.snapshot().get("truncated"))
                || !(evidence.snapshot().get("text") instanceof String text)
                || !(evidence.snapshot().get("chunkHash") instanceof String hash)
                || !SupportJson.hash(text).equals(hash)) return false;
        return !jdbc.queryForList("""
                SELECT policy_code FROM support_runbook_policy
                WHERE doc_id=:doc AND content_hash=:hash AND policy_code='ACTIVATION_RECONCILE_V1'
                """ + (lock ? " FOR SHARE" : ""), Map.of("doc", evidence.sourceRef(), "hash", hash), String.class).isEmpty();
    }

    private void requireUnexpired(Proposal proposal) {
        if (expired(proposal)) throw error(REPAIR_EXPIRED);
    }

    private boolean expired(Proposal proposal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp()>=:expires",
                Map.of("expires", proposal.expiresAt()), Boolean.class));
    }

    private void audit(Context context, String proposal, String operation, String type, String hash) {
        jdbc.update("""
                INSERT INTO support_audit_event (id,tenant_id,case_id,run_id,operation_id,actor_id,type,safe_payload)
                VALUES (:id,:tenant,:caseId,:run,NULLIF(:operation,''),:actor,:type,CAST(:payload AS jsonb))
                """, Map.of("id", id(), "tenant", context.supportCase().tenantId(), "caseId", context.supportCase().id(),
                "run", context.run().id(), "operation", operation == null ? "" : operation, "actor", context.actor(),
                "type", type, "payload", SupportJson.write(Map.of("proposalId", proposal, "payloadHash", hash))));
    }

    private static Set<String> citations(Map<String, Object> diagnosis) {
        Set<String> ids = new HashSet<>();
        if (diagnosis.get("facts") instanceof List<?> facts) {
            for (Object item : facts) {
                if (item instanceof Map<?, ?> fact && fact.get("evidenceIds") instanceof List<?> refs) {
                    for (Object ref : refs) if (ref instanceof String id) ids.add(id);
                }
            }
        }
        return ids;
    }

    private static boolean isCited(SupportRunStore.Evidence e, Set<String> citations, String kind, String target) {
        return kind.equals(e.kind()) && target.equals(e.sourceRef()) && citations.contains(e.id());
    }

    private static long observedVersion(SupportRunStore.Evidence subscription) {
        if (subscription.snapshot().get("version") instanceof Number n && Long.toString(n.longValue()).equals(subscription.version()))
            return n.longValue();
        throw error(REPAIR_STALE);
    }

    private static String eventId(SupportRunStore.Evidence timeline, String type) {
        if (timeline.snapshot().get("items") instanceof List<?> events) {
            for (Object event : events) {
                if (event instanceof Map<?, ?> detail && type.equals(detail.get("eventType"))
                        && detail.get("eventId") instanceof String id) return id;
            }
        }
        throw error(REPAIR_STALE);
    }

    private static String hash(Context context, String id, Basis basis, OffsetDateTime expires, SupportRunStore.Evidence runbook) {
        var c = context.supportCase();
        return SupportJson.hash(SupportJson.write(List.of(id, ACTION, context.run().id(), context.run().actorId(), c.id(),
                c.tenantId(), c.resourceId(), basis.expectedVersion(), basis.caseVersion(), basis.payment(), basis.failure(),
                basis.runbook(), runbook.hash(), expires.toInstant().toString())));
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static SupportException error(SupportException.Code code) { return new SupportException(code); }
}
