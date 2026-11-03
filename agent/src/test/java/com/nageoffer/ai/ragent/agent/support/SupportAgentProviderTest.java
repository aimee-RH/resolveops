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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.service.RunbookEvidenceSearchService;
import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportAgentProviderTest {
    @Test
    void eachInvestigationHasIndependentStateAndOnlyFourReadTools() {
        var properties = new AgentProperties();
        properties.getChat().setProvider("ollama");
        properties.getChat().setModel("resolveops-qwen3:4b");
        properties.getChat().setTemperature(0.0);
        var provider = new SupportAgentProvider(mock(OpenAIChatModel.class), properties,
                mock(SupportReadService.class), mock(SupportRunStore.class),
                new StaticListableBeanFactory().getBeanProvider(RunbookEvidenceSearchService.class));
        var scope = new SupportRunScope("run", "actor", "tenant", "case", "sub", SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
        try (var first = provider.create(scope); var second = provider.create(scope)) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.getStateStore()).isNotSameAs(second.getStateStore());
            assertThat(first.getToolkit()).isNotSameAs(second.getToolkit());
            assertThat(first.getToolkit().getToolNames()).containsExactlyInAnyOrder(
                    "get_case_context", "get_subscription_status", "list_subscription_events", "search_runbooks");
            first.getToolkit().getToolNames().forEach(name -> assertThat(first.getToolkit().getTool(name).isReadOnly()).isTrue());
            assertThat(first.getGenerateOptions().getReasoningEffort()).isEqualTo("none");
        }
        assertThatThrownBy(() -> provider.create(new SupportRunScope("run", "actor", "tenant", "case", "api", SupportCase.Scenario.API_AUTHORIZATION)))
                .hasMessage("CONFIGURATION_REQUIRED");
    }
}
