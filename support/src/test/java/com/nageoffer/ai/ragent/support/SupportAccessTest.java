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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportAccessTest extends PostgresFixture {
    private SupportAccessService access() { return new SupportAccessService(jdbc); }

    private SupportRunScope scope() {
        sql.update("INSERT INTO support_run(id, tenant_id, case_id, actor_id, session_id, status) VALUES ('run-a', 'tenant-a', 'case-a', 'operator', 'support:run-a', 'RUNNING')");
        return new SupportRunScope("run-a", "operator", "tenant-a", "case-a", "sub-a", SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
    }

    @Test
    void listAndDirectAccessRespectTenantGrant() {
        assertThat(access().listCases("operator")).extracting(SupportCase::id).containsExactly("case-a");
        for (String id : new String[]{"case-b", "missing"}) {
            assertThatThrownBy(() -> access().requireCase("operator", id, SupportAccessService.Permission.READ))
                    .isInstanceOf(SupportException.class).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        }
    }

    @Test
    void viewerCannotInvestigateAndMissingIdentityCannotRead() {
        assertThatThrownBy(() -> access().requireCase("viewer", "case-a", SupportAccessService.Permission.INVESTIGATE))
                .hasMessage("NOT_FOUND_OR_FORBIDDEN");
        assertThatThrownBy(() -> access().listCases(null)).hasMessage("NOT_AUTHENTICATED");
    }

    @Test
    void forgedScopeAndRevokedAccessAreRejected() {
        SupportRunScope real = scope();
        SupportRunScope forged = new SupportRunScope(real.runId(), real.actorId(), real.tenantId(), real.caseId(), "sub-b", real.scenario());
        assertThatThrownBy(() -> access().requireScope(forged)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        sql.update("DELETE FROM support_access_grant WHERE user_id='operator'");
        assertThatThrownBy(() -> access().requireScope(real)).hasMessage("NOT_FOUND_OR_FORBIDDEN");
    }

    @Test
    void readsAreScopedAndTimelineIsExplicitlyTruncated() {
        SupportRunScope scope = scope();
        SupportReadService reads = new SupportReadService(jdbc, access());
        assertThat(reads.subscription(scope)).containsEntry("subscriptionRef", "sub-a").containsEntry("version", 7L);
        assertThat(reads.subscriptionEvents(scope, Instant.now().minusSeconds(86400), Instant.now(), 1))
                .containsEntry("truncated", true);
        assertThatThrownBy(() -> reads.subscriptionEvents(scope, Instant.now().minusSeconds(86400 * 32L), Instant.now(), 10))
                .hasMessage("INVALID_ARGUMENT");
        assertThatThrownBy(() -> reads.subscriptionEvents(scope, Instant.now().minusSeconds(100), Instant.now(), 101))
                .hasMessage("INVALID_ARGUMENT");
    }
}
