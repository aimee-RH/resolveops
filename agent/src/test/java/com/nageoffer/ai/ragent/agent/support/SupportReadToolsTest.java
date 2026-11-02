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

package com.nageoffer.ai.ragent.agent.support;

import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SupportReadToolsTest {
    private ToolCallParam param(Map<String, Object> input) {
        var scope = new SupportRunScope("run", "actor", "tenant", "case", "sub", SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
        return ToolCallParam.builder().input(input).runtimeContext(RuntimeContext.builder().userId("actor").sessionId("support:run")
                .put(SupportRunScope.class, scope).put(SupportBudget.class, new SupportBudget()).build()).build();
    }

    @Test
    void toolsExposeNoWritesOrCallerControlledIdentity() {
        var tools = SupportReadTools.build(SupportCase.Scenario.SUBSCRIPTION_ACTIVATION, mock(SupportReadService.class), mock(SupportRunStore.class), null);
        assertThat(tools.getToolNames()).containsExactlyInAnyOrder("get_case_context", "get_subscription_status", "list_subscription_events", "search_runbooks");
        tools.getToolNames().forEach(name -> assertThat(tools.getTool(name).isReadOnly()).isTrue());
        var reads = mock(SupportReadService.class);
        var tool = new SupportReadTools.ReadTool(SupportReadTools.Kind.SUBSCRIPTION, reads, mock(SupportRunStore.class), null);
        var result = tool.callAsync(param(Map.of("tenantId", "other"))).block();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("INVALID_ARGUMENT");
        verifyNoInteractions(reads);
    }

    @Test
    void missingRuntimeScopeAndCancelledRunCannotQuery() {
        var reads = mock(SupportReadService.class);
        var tool = new SupportReadTools.ReadTool(SupportReadTools.Kind.CASE, reads, mock(SupportRunStore.class), null);
        assertThat(tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block().getState()).isEqualTo(ToolResultState.ERROR);
        var request = param(Map.of());
        request.getRuntimeContext().get(SupportBudget.class).cancel();
        assertThat(tool.callAsync(request).block().getState()).isEqualTo(ToolResultState.INTERRUPTED);
        verifyNoInteractions(reads);
    }

    @Test
    void storageExceptionIsSanitizedAndNeverReportedAsEmptySuccess() {
        var reads = mock(SupportReadService.class);
        doThrow(new IllegalStateException("jdbc:postgresql://private password=secret")).when(reads).authorize(any());
        var tool = new SupportReadTools.ReadTool(SupportReadTools.Kind.CASE, reads, mock(SupportRunStore.class), null);
        var result = tool.callAsync(param(Map.of())).block();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("UNAVAILABLE").doesNotContain("password", "private");
    }

    @Test
    void successfulReadReturnsPersistedEvidenceWithObservationTime() {
        var reads = mock(SupportReadService.class);
        var runs = mock(SupportRunStore.class);
        when(reads.subscription(any())).thenReturn(Map.of("version", 7L, "activationState", "PENDING"));
        when(runs.record(any(), eq("SUBSCRIPTION"), eq("sub"), eq("7"), anyMap()))
                .thenReturn(new SupportRunStore.Evidence("evidence-1", "SUBSCRIPTION", "sub", "7", "hash",
                        Instant.parse("2026-11-02T10:00:00Z"), Map.of("activationState", "PENDING")));
        var tool = new SupportReadTools.ReadTool(SupportReadTools.Kind.SUBSCRIPTION, reads, runs, null);
        var result = tool.callAsync(param(Map.of())).block();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("evidence-1", "observedAt", "PENDING");
    }

    @Test
    void concurrentCallsShareOneBudget() {
        var budget = new SupportBudget();
        assertThat(IntStream.range(0, 100).parallel().filter(index -> budget.acquire()).count()).isEqualTo(12);
    }
}
