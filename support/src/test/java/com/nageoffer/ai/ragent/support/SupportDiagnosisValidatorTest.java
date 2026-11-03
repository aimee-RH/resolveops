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

import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SupportDiagnosisValidatorTest extends PostgresFixture {
    private SupportRunStore runs() { return new SupportRunStore(jdbc, new SupportAccessService(jdbc)); }
    private SupportDiagnosisValidator validator() { return new SupportDiagnosisValidator(new SupportAccessService(jdbc), runs(), jdbc); }
    private SupportRunScope scope() {
        var scope = runs().create("operator", "case-a", "diagnosis", Map.of("mode", "test"));
        runs().start(scope);
        return scope;
    }
    private Map<String, Object> diagnosis(String outcome, String evidence) {
        var value = new LinkedHashMap<String, Object>();
        value.put("outcome", outcome);
        value.put("facts", List.of(Map.of("text", "Observed subscription state", "evidenceIds", List.of(evidence))));
        value.put("hypotheses", List.of());
        value.put("missingInformation", List.of());
        value.put("recommendedAction", null);
        value.put("runbookEvidenceIds", List.of());
        return value;
    }

    @Test
    void repairNeedsObservedFactsAndExactCuratedPolicyButNeverWritesSubscription() {
        var scope = scope();
        var reads = new SupportReadService(jdbc, new SupportAccessService(jdbc));
        var sub = runs().record(scope, "SUBSCRIPTION", "sub-a", "7", reads.subscription(scope));
        var events = runs().record(scope, "EVENTS", "sub-a", null,
                reads.subscriptionEvents(scope, Instant.now().minusSeconds(86400), Instant.now(), 20));
        String hash = "a".repeat(64);
        var manual = runs().record(scope, "RUNBOOK", "runbook-a", hash,
                Map.of("text", "Reconcile confirmed activation failures after approval.", "chunkHash", hash, "truncated", false));
        var value = diagnosis("PROPOSE_REPAIR", sub.id());
        value.put("facts", List.of(Map.of("text", "Paid and activation failed", "evidenceIds", List.of(sub.id(), events.id()))));
        value.put("recommendedAction", Map.of("type", "RECONCILE_ACTIVATION"));
        value.put("runbookEvidenceIds", List.of(manual.id()));
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(value))).hasMessage("INVALID_DIAGNOSIS");
        sql.update("INSERT INTO support_runbook_policy VALUES ('runbook-a', ?, 'ACTIVATION_RECONCILE_V1')", hash);
        assertThat(validator().validate(scope, SupportJson.write(value))).containsEntry("outcome", "PROPOSE_REPAIR");
        assertThat(sql.queryForObject("SELECT activation_state FROM support_subscription WHERE id='sub-a'", String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("SELECT count(*) FROM support_action_operation", Integer.class)).isZero();
        value.put("facts", List.of(Map.of("text", "Only a status, without timeline", "evidenceIds", List.of(sub.id()))));
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(value))).hasMessage("INVALID_DIAGNOSIS");
    }

    @Test
    void noActionRequiresActiveEvidenceAndMissingInformationRequiresAnExplanation() {
        var scope = scope();
        var pending = runs().record(scope, "SUBSCRIPTION", "sub-a", "7", Map.of("activationState", "PENDING"));
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(diagnosis("NO_ACTION_NEEDED", pending.id()))))
                .hasMessage("INVALID_DIAGNOSIS");
        var active = runs().record(scope, "SUBSCRIPTION", "sub-a", "8", Map.of("activationState", "ACTIVE"));
        assertThat(validator().validate(scope, SupportJson.write(diagnosis("NO_ACTION_NEEDED", active.id())))).containsEntry("outcome", "NO_ACTION_NEEDED");
        var missing = diagnosis("NEEDS_INFORMATION", pending.id());
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(missing))).hasMessage("INVALID_DIAGNOSIS");
        missing.put("missingInformation", List.of("Approved runbook is unavailable"));
        assertThat(validator().validate(scope, SupportJson.write(missing))).containsEntry("outcome", "NEEDS_INFORMATION");
    }

    @Test
    void fabricatedOtherRunAndOtherTenantEvidenceAreRejected() {
        var scope = scope();
        var second = runs().create("operator", "case-a", "second", Map.of("mode", "test"));
        runs().start(second);
        var unrelated = runs().record(second, "SUBSCRIPTION", "sub-a", "7", Map.of("activationState", "ACTIVE"));
        var foreign = runs().create("other", "case-b", "foreign", Map.of("mode", "test"));
        runs().start(foreign);
        var secret = runs().record(foreign, "SUBSCRIPTION", "sub-b", "1", Map.of("activationState", "ACTIVE"));
        for (String id : List.of("invented", unrelated.id(), secret.id()))
            assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(diagnosis("HANDOFF", id))))
                    .hasMessage("INVALID_DIAGNOSIS");
    }

    @Test
    void unapprovedActionsUnknownFieldsDuplicateJsonAndRevokedAccessFailClosed() {
        var scope = scope();
        var evidence = runs().record(scope, "CASE", "case-a", null, Map.of("description", "Untrusted"));
        var valid = diagnosis("HANDOFF", evidence.id());
        String json = SupportJson.write(valid);
        assertThatThrownBy(() -> validator().validate(scope, "```json\n" + json + "\n```" )).hasMessage("INVALID_DIAGNOSIS");
        assertThatThrownBy(() -> validator().validate(scope, json + "{}" )).hasMessage("INVALID_DIAGNOSIS");
        assertThatThrownBy(() -> validator().validate(scope, json.replaceFirst("\\{", "{\"outcome\":\"HANDOFF\",")))
                .hasMessage("INVALID_DIAGNOSIS");
        valid.put("tenantId", "tenant-b");
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(valid))).hasMessage("INVALID_DIAGNOSIS");
        valid.remove("tenantId");
        valid.put("recommendedAction", Map.of("type", "RECONCILE_ACTIVATION"));
        assertThatThrownBy(() -> validator().validate(scope, SupportJson.write(valid))).hasMessage("INVALID_DIAGNOSIS");
        sql.update("DELETE FROM support_access_grant WHERE user_id='operator'");
        assertThatThrownBy(() -> validator().validate(scope, json)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
    }
}
