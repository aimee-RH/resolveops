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

package com.nageoffer.ai.ragent.support;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.repair.SupportRepairService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import com.nageoffer.ai.ragent.support.web.SupportExceptionAdvice;
import com.nageoffer.ai.ragent.support.web.SupportRepairController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportRepairTest extends PostgresFixture {
    private SupportAccessService access;
    private SupportRunStore runs;
    private SupportRepairService repairs;

    @BeforeEach
    void createRepairService() {
        access = new SupportAccessService(jdbc);
        runs = new SupportRunStore(jdbc, access);
        var service = new SupportRepairService(jdbc, access, runs);
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new JdbcTransactionManager(sql.getDataSource()));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(transactions);
        repairs = (SupportRepairService) proxy.getProxy();
    }

    @Test
    void authorizedHttpWorkflowPersistsEvidenceApprovalAndVerifiedReceipt() throws Exception {
        String run = investigation("first");
        String path = "/support/runs/" + run + "/repair";
        var mvc = MockMvcBuilders.standaloneSetup(new SupportRepairController(repairs))
                .setControllerAdvice(new SupportExceptionAdvice()).build();
        try {
            mvc.perform(post(path).header("X-User-Id", "operator")).andExpect(status().isUnauthorized());
            UserContext.set(LoginUser.builder().userId("viewer").build());
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canPropose").value(false));
            mvc.perform(post(path)).andExpect(status().isNotFound());

            UserContext.set(LoginUser.builder().userId("operator").build());
            mvc.perform(post(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.proposal.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.proposal.expiresAt").isString());
            var proposal = repairs.get("operator", run).proposal();
            String body = SupportJson.write(Map.of("payloadHash", proposal.payloadHash()));
            mvc.perform(post(path + "/execute").contentType("application/json").content(body)).andExpect(status().isConflict());
            assertUnchanged();

            UserContext.set(LoginUser.builder().userId("other").build());
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(post(path + "/approve").contentType("application/json").content(body)).andExpect(status().isNotFound());

            UserContext.set(LoginUser.builder().userId("approver").build());
            mvc.perform(post(path + "/approve").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.proposal.approverId").value("approver"));
            assertUnchanged();

            UserContext.set(LoginUser.builder().userId("operator").build());
            mvc.perform(post(path + "/execute").contentType("application/json").content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.proposal.status").value("VERIFIED"))
                    .andExpect(jsonPath("$.data.proposal.receipt.subscriptionVersion").value(8));
            assertVerified();
            assertThat(sql.queryForList("SELECT type FROM support_audit_event ORDER BY created_at", String.class))
                    .containsExactly("REPAIR_PROPOSED", "REPAIR_APPROVED", "REPAIR_VERIFIED");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void proposalRetriesAndConcurrentExecutionReturnTheSameCommittedReceipt() throws Exception {
        String run = investigation("retry");
        var proposals = race(() -> repairs.propose("operator", run).proposal(), () -> repairs.propose("operator", run).proposal());
        assertThat(proposals.get(0)).isEqualTo(proposals.get(1));
        String hash = proposals.get(0).payloadHash();
        repairs.review("approver", run, hash, true);
        repairs.review("approver", run, hash, true);
        var results = race(() -> repairs.execute("operator", run, hash).proposal().receipt(),
                () -> repairs.execute("operator", run, hash).proposal().receipt());
        assertThat(results.get(0)).isEqualTo(results.get(1)).isNotNull();

        // Treat the first response as lost. A retry after the version change cannot repeat the repair.
        assertThat(repairs.execute("operator", run, hash).proposal().receipt()).isEqualTo(results.get(0));
        assertThat(count("support_action_proposal")).isEqualTo(1);
        assertThat(count("support_audit_event")).isEqualTo(3);
        assertVerified();
    }

    @Test
    void distinctApprovedRunsCannotRepairTheSameSubscriptionVersionTwice() throws Exception {
        String first = investigation("first");
        String second = investigation("second");
        String firstHash = approved(first);
        String secondHash = approved(second);
        var results = race(() -> executeOutcome(first, firstHash), () -> executeOutcome(second, secondHash));
        assertThat(results).containsExactlyInAnyOrder("VERIFIED", "REPAIR_STALE");
        assertVerified();
    }

    @Test
    void rejectsHashMismatchSelfApprovalAndExecutionOfRejectedProposal() {
        String run = investigation("reject");
        var proposal = repairs.propose("operator", run).proposal();
        sql.update("INSERT INTO support_access_grant VALUES ('operator','tenant-a','APPROVER')");
        String hash = proposal.payloadHash();
        assertThatThrownBy(() -> repairs.review("operator", run, hash, true)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        assertThatThrownBy(() -> repairs.review("approver", run, "0".repeat(64), true)).hasMessage("REPAIR_STALE");
        assertThat(repairs.review("approver", run, hash, false).proposal().status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("CONFLICT");
        assertThatThrownBy(() -> repairs.review("approver", run, hash, true)).hasMessage("CONFLICT");
        assertUnchanged();
    }

    @Test
    void staleSubscriptionOrCaseCannotAdvanceTheApprovedAction() {
        String run = investigation("stale");
        String hash = repairs.propose("operator", run).proposal().payloadHash();
        sql.update("UPDATE support_subscription SET version=8 WHERE id='sub-a'");
        assertThatThrownBy(() -> repairs.review("approver", run, hash, true)).hasMessage("REPAIR_STALE");
        sql.update("UPDATE support_subscription SET version=7 WHERE id='sub-a'");
        repairs.review("approver", run, hash, true);
        sql.update("UPDATE support_case SET version=version+1 WHERE id='case-a'");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("REPAIR_STALE");
        assertUnchanged();
    }

    @Test
    void revokedAuthorizationOrRetiredRunbookCannotExecute() {
        String run = investigation("expiry");
        String hash = approved(run);
        sql.update("DELETE FROM support_access_grant WHERE user_id='approver'");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        sql.update("INSERT INTO support_access_grant VALUES ('approver','tenant-a','APPROVER')");
        sql.update("DELETE FROM support_access_grant WHERE user_id='operator'");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        sql.update("INSERT INTO support_access_grant VALUES ('operator','tenant-a','OPERATOR')");
        sql.update("DELETE FROM support_runbook_policy");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("REPAIR_STALE");
        assertUnchanged();
    }

    @Test
    void expiredApprovalCannotBeUsedEvenWithCorrectPayloadHash() {
        String run = investigation("expiry");
        String hash = approved(run);
        // Move the fixture deadline into the past to exercise expiry without waiting 15 minutes.
        sql.update("UPDATE support_action_proposal SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE run_id=?", run);
        assertThat(repairs.get("operator", run).proposal().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).hasMessage("REPAIR_EXPIRED");
        assertUnchanged();
    }

    @Test
    void transactionRollsBackSubscriptionEventAndReceiptWhenAuditWriteFails() {
        String run = investigation("rollback");
        String hash = approved(run);
        sql.execute("""
                ALTER TABLE support_audit_event ADD CONSTRAINT simulate_audit_write_failure
                CHECK (type <> 'REPAIR_VERIFIED')
                """);
        assertThatThrownBy(() -> repairs.execute("operator", run, hash)).isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged();
        assertThat(count("support_audit_event")).isEqualTo(2);
        sql.execute("ALTER TABLE support_audit_event DROP CONSTRAINT simulate_audit_write_failure");
        assertThat(repairs.execute("operator", run, hash).proposal().status()).isEqualTo("VERIFIED");
        assertVerified();
    }

    private String investigation(String request) {
        var scope = runs.create("operator", "case-a", request, Map.of("mode", "test"));
        runs.start(scope);
        var reads = new SupportReadService(jdbc, access);
        var sub = runs.record(scope, "SUBSCRIPTION", "sub-a", "7", reads.subscription(scope));
        var events = runs.record(scope, "EVENTS", "sub-a", null,
                reads.subscriptionEvents(scope, Instant.now().minusSeconds(86_400), Instant.now(), 20));
        String text = "Recommend reconciliation after confirming payment and failed activation.";
        String hash = SupportJson.hash(text);
        runs.record(scope, "RUNBOOK", "activation-sop", hash, Map.of("text", text, "chunkHash", hash, "truncated", false));
        sql.update("INSERT INTO support_runbook_policy VALUES ('activation-sop', ?, 'ACTIVATION_RECONCILE_V1') ON CONFLICT DO NOTHING", hash);
        var diagnosis = new SupportDiagnosisValidator(access, runs, jdbc).complete(scope,
                SupportJson.write(Map.of("facts", List.of(Map.of("text", "Paid with activation failure recorded",
                        "evidenceIds", List.of(sub.id(), events.id()))))));
        runs.finish(scope, "COMPLETED", diagnosis);
        return scope.runId();
    }

    private String approved(String run) {
        String hash = repairs.propose("operator", run).proposal().payloadHash();
        repairs.review("approver", run, hash, true);
        return hash;
    }

    private String executeOutcome(String run, String hash) {
        try { return repairs.execute("operator", run, hash).proposal().status(); }
        catch (SupportException ex) { return ex.code().name(); }
    }

    private void assertUnchanged() {
        assertThat(sql.queryForObject("SELECT activation_state FROM support_subscription WHERE id='sub-a'", String.class))
                .isEqualTo("PENDING");
        assertThat(count("support_action_operation")).isZero();
        assertThat(repairEventCount()).isZero();
    }

    private void assertVerified() {
        assertThat(sql.queryForMap("SELECT activation_state, version FROM support_subscription WHERE id='sub-a'"))
                .containsEntry("activation_state", "ACTIVE").containsEntry("version", 8L);
        assertThat(sql.queryForMap("SELECT status,attempts FROM support_action_operation"))
                .containsEntry("status", "VERIFIED").containsEntry("attempts", 1);
        assertThat(repairEventCount()).isEqualTo(1);
    }

    private int repairEventCount() {
        return sql.queryForObject("SELECT count(*) FROM support_subscription_event WHERE event_type='ACTIVATION_REPAIRED'", Integer.class);
    }

    private int count(String table) {
        return sql.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
