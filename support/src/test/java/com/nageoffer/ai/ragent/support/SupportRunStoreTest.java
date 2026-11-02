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
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportRunStoreTest extends PostgresFixture {
    private SupportRunStore store() { return new SupportRunStore(jdbc, new SupportAccessService(jdbc)); }

    @Test
    void repeatedRequestDoesNotCreateAnotherRunAndChangedRequestConflicts() {
        SupportRunStore store = store();
        SupportRunScope first = store.create("operator", "case-a", "request-1", Map.of("mode", "test"));
        assertThat(store.create("operator", "case-a", "request-1", Map.of("mode", "test"))).isEqualTo(first);
        assertThatThrownBy(() -> store.create("operator", "case-a", "request-1", Map.of("mode", "changed")))
                .hasMessage("CONFLICT");
        assertThat(sql.queryForObject("SELECT count(*) FROM support_run", Integer.class)).isEqualTo(1);
    }

    @Test
    void evidenceAndReplayAreBoundToAuthorizedRunAndImmutableSnapshot() {
        SupportRunStore store = store();
        SupportRunScope scope = store.create("operator", "case-a", "request-1", Map.of("mode", "test"));
        assertThat(store.start(scope)).isTrue();
        assertThat(store.start(scope)).isFalse();
        var evidence = store.record(scope, "SUBSCRIPTION", "sub-a", "7", Map.of("activationState", "PENDING"));
        sql.update("UPDATE support_subscription SET activation_state='ACTIVE', version=8 WHERE id='sub-a'");
        assertThat(store.evidence("approver", scope.runId(), evidence.id()).snapshot()).containsEntry("activationState", "PENDING");
        assertThatThrownBy(() -> store.evidence("other", scope.runId(), evidence.id())).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        store.event(scope, "TOOL_COMPLETED", Map.of("evidenceId", evidence.id()));
        assertThat(store.events("operator", scope.runId(), 0)).hasSize(1);
        assertThat(store.finish(scope, "COMPLETED", Map.of("outcome", "NO_ACTION_NEEDED"))).isTrue();
        assertThat(store.finish(scope, "INTERRUPTED", Map.of())).isFalse();
        assertThatThrownBy(() -> store.record(scope, "SUBSCRIPTION", "sub-a", "8", Map.of())).hasMessage("NOT_FOUND_OR_FORBIDDEN");
    }
}
