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
import com.nageoffer.ai.ragent.support.repair.SupportRepairService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SupportApiInvestigationTest extends PostgresFixture {
    private SupportReadService reads;
    private SupportRunStore runs;
    private SupportDiagnosisValidator diagnoses;
    private SupportRunScope scope;

    @BeforeEach
    void setupApiCase() {
        var access = new SupportAccessService(jdbc);
        reads = new SupportReadService(jdbc, access);
        runs = new SupportRunStore(jdbc, access);
        diagnoses = new SupportDiagnosisValidator(access, runs, jdbc);
        sql.update("""
                INSERT INTO support_integration VALUES
                ('integration-a','tenant-a',true,'{"grantedScopes":["PRIVATE_SCOPE"],"token":"PRIVATE_TOKEN"}',7),
                ('integration-sibling','tenant-a',true,'{}',1), ('integration-b','tenant-b',true,'{}',1)
                """);
        sql.update("""
                INSERT INTO support_case(id,tenant_id,scenario,resource_id,description)
                VALUES ('api-a','tenant-a','API_AUTHORIZATION','integration-a','API calls are being denied')
                """);
        scope = runs.create("operator", "api-a", "api", Map.of("mode", "test"));
        runs.start(scope);
    }

    @Test
    void readsExposeFixedWindowAndOnlyApprovedMetadataWithinCaseScope() {
        log("right", "tenant-a", "integration-a", 401, "TOKEN_EXPIRED", 1);
        log("unknown", "tenant-a", "integration-a", 403, "PRIVATE_UPPERCASE_CODE", 2);
        log("different-tenant", "tenant-b", "integration-b", 403, "MISSING_SCOPE", 1);
        log("different-integration", "tenant-a", "integration-sibling", 403, "MISSING_SCOPE", 1);
        log("old", "tenant-a", "integration-a", 403, "MISSING_SCOPE", 48);
        log("success", "tenant-a", "integration-a", 200, "MISSING_SCOPE", 1);

        var integration = reads.integration(scope);
        assertThat(integration).containsOnlyKeys("integrationRef", "enabled", "version", "grantedScopeCount")
                .containsEntry("enabled", true).containsEntry("grantedScopeCount", 1);
        var logs = reads.apiDenials(scope);
        assertThat(logs.get("items")).asList().hasSize(2);
        assertThat(SupportJson.write(logs)).contains("TOKEN_EXPIRED", "UNCLASSIFIED").doesNotContain("MISSING_SCOPE", "PRIVATE_");
        var forged = new SupportRunScope(scope.runId(), scope.actorId(), scope.tenantId(), scope.caseId(),
                "integration-sibling", scope.scenario());
        assertThatThrownBy(() -> reads.apiDenials(forged)).hasMessage("NOT_FOUND_OR_FORBIDDEN");

        for (int i = 0; i < 11; i++) log("recent-" + i, "tenant-a", "integration-a", 403, "MISSING_SCOPE", 0);
        logs = reads.apiDenials(scope);
        assertThat(logs).containsEntry("truncated", true);
        assertThat(logs.get("items")).asList().hasSize(10);
    }

    @Test
    void distinguishesRecordedExpiredTokenAndMissingScopeWithoutOpeningRepair() {
        log("expired", "tenant-a", "integration-a", 401, "TOKEN_EXPIRED", 1);
        var expired = currentDiagnosis();
        assertThat(expired).containsEntry("outcome", "HANDOFF").containsEntry("recommendedAction", null);
        assertThat(SupportJson.write(expired.get("hypotheses"))).contains("expired token");

        sql.update("DELETE FROM support_api_request_log WHERE id='expired'");
        log("scope", "tenant-a", "integration-a", 403, "MISSING_SCOPE", 1);
        var missingScope = currentDiagnosis();
        assertThat(missingScope).containsEntry("outcome", "HANDOFF").containsEntry("recommendedAction", null);
        assertThat(SupportJson.write(missingScope.get("hypotheses"))).contains("missing scope").doesNotContain("expired token");
        var repairs = new SupportRepairService(jdbc, new SupportAccessService(jdbc), runs);
        assertThatThrownBy(() -> repairs.get("operator", scope.runId())).hasMessage("NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void emptyWindowRequiresInformationAndMismatchedCodeDoesNotEstablishCause() {
        var empty = currentDiagnosis();
        assertThat(empty).containsEntry("outcome", "NEEDS_INFORMATION").containsEntry("recommendedAction", null);
        assertThat(empty.get("missingInformation")).asList().containsExactly("A recent HTTP 401/403 record for this integration");
        log("mismatch", "tenant-a", "integration-a", 403, "TOKEN_EXPIRED", 1);
        var ambiguous = currentDiagnosis();
        assertThat(ambiguous).containsEntry("outcome", "HANDOFF");
        assertThat(ambiguous.get("hypotheses")).asList()
                .containsExactly("The denial records do not identify a supported reason. Review them with the integration owner.");
    }

    @Test
    void rejectsWrongScenarioEvidenceAndRetainsExplicitMissingIntegrationStatus() {
        assertThatThrownBy(() -> reads.subscription(scope)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        var otherResource = runs.record(scope, "INTEGRATION", "integration-sibling", "1", Map.of("enabled", true));
        assertThatThrownBy(() -> diagnoses.completeApi(scope)).hasMessage("INVALID_DIAGNOSIS");
        var caseEvidence = runs.record(scope, "CASE", scope.caseId(), null, reads.caseContext(scope));
        var result = diagnoses.completeApi(scope);
        assertThat(result).containsEntry("outcome", "NEEDS_INFORMATION").containsEntry("recommendedAction", null);
        assertThat(result.get("missingInformation")).asList()
                .containsExactly("Current integration status", "A recent HTTP 401/403 record for this integration");
        assertThat(SupportJson.write(result.get("facts"))).contains(caseEvidence.id()).doesNotContain(otherResource.id());
    }

    private Map<String, Object> currentDiagnosis() {
        runs.record(scope, "INTEGRATION", scope.resourceId(), "7", reads.integration(scope));
        runs.record(scope, "API_DENIALS", scope.resourceId(), null, reads.apiDenials(scope));
        return diagnoses.completeApi(scope);
    }

    private void log(String id, String tenant, String integration, int status, String code, int hoursAgo) {
        sql.update("""
                INSERT INTO support_api_request_log
                (id,tenant_id,integration_id,occurred_at,route_template,status_code,error_code,safe_auth_details)
                VALUES (?,?,?,clock_timestamp() - (? * INTERVAL '1 hour'),'/PRIVATE_ROUTE',?,?,'{"value":"PRIVATE_PAYLOAD"}')
                """, id, tenant, integration, hoursAgo, status, code);
    }
}
