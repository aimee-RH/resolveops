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

import com.alibaba.fastjson2.JSON;
import com.nageoffer.ai.ragent.agent.config.AgentEngineConfiguration;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportJson;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Opt-in compatibility checks with synthetic business data, not a task-success benchmark.
@EnabledIfEnvironmentVariable(named = "RESOLVEOPS_LOCAL_MODEL_TEST", matches = "1")
class LocalModelSmokeTest {
    @Test
    void nativeEndpointGeneratesSchemaConstrainedJson() throws Exception {
        var body = Map.of("model", "resolveops-qwen3:4b", "stream", false, "think", false,
                "messages", List.of(Map.of("role", "user", "content", "Return JSON with status READY.")),
                "format", Map.of("type", "object", "properties", Map.of("status",
                        Map.of("type", "string", "enum", List.of("READY"))),
                        "required", List.of("status"), "additionalProperties", false));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/chat"))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(SupportJson.write(body))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JSON.parseObject(response.body());
        assertThat(json.getString("done_reason")).isEqualTo("stop");
        assertThat(SupportJson.read(json.getJSONObject("message").getString("content")))
                .containsExactlyEntriesOf(Map.of("status", "READY"));
        Files.writeString(Path.of("target/local-model-schema.json"), response.body());
    }

    @Test
    void projectStreamingClientCallsScopedReadToolAndUsesItsEvidence() throws Exception {
        var binder = LocalModelConfigurationTest.configuration();
        var model = new AgentEngineConfiguration(binder.bind("agent", AgentProperties.class).get(),
                binder.bind("ai", AIModelProperties.class).get()).agentChatModel();
        var reads = mock(SupportReadService.class);
        var runs = mock(SupportRunStore.class);
        var scope = new SupportRunScope("deploy-run", "deploy-actor", "synthetic-tenant", "synthetic-case",
                "synthetic-subscription", SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
        when(reads.subscription(scope)).thenReturn(Map.of("version", 1, "paymentState", "PAID", "activationState", "PENDING"));
        when(runs.record(eq(scope), eq("SUBSCRIPTION"), eq("synthetic-subscription"), eq("1"), anyMap()))
                .thenReturn(new SupportRunStore.Evidence("deploy-evidence-1", "SUBSCRIPTION", "synthetic-subscription",
                        "1", "synthetic-hash", Instant.parse("2026-11-02T00:00:00Z"),
                        Map.of("paymentState", "PAID", "activationState", "PENDING")));
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new SupportReadTools.ReadTool(SupportReadTools.Kind.SUBSCRIPTION, reads, runs, null));
        var agent = ReActAgent.builder().name("local-deployment-check").model(model).toolkit(toolkit)
                .stateStore(new InMemoryAgentStateStore()).maxIters(3).maxRetries(1)
                .sysPrompt("This is a deployment compatibility check with synthetic data. "
                        + "You must call get_subscription_status once before answering. "
                        + "Use its evidence only. Never repair or invent subscription state.").build();
        var context = RuntimeContext.builder().userId("deploy-actor").sessionId("support:deploy-run")
                .put(SupportRunScope.class, scope).put(SupportBudget.class, new SupportBudget()).build();
        long started = System.nanoTime();
        var reply = agent.call(List.of(Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("Check this subscription. Report the exact evidence ID, "
                        + "paymentState and activationState returned by the tool.").build()).build()), context)
                .block(Duration.ofSeconds(120));
        assertThat(reply).isNotNull();
        String text = reply.getContent().stream().filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).collect(Collectors.joining("\n"));
        Files.writeString(Path.of("target/local-model-agent.json"), SupportJson.write(Map.of(
                "scope", "compatibility only; synthetic data; mocked persistence and authorization",
                "model", "resolveops-qwen3:4b", "observedAt", Instant.now(), "answer", text,
                "elapsedMs", (System.nanoTime() - started) / 1_000_000)));
        verify(reads).authorize(scope);
        verify(reads).subscription(scope);
        verify(runs).event(eq(scope), eq("TOOL_COMPLETED"), anyMap());
        assertThat(text).contains("deploy-evidence-1", "PAID", "PENDING").doesNotContain("<think>", "</think>");
    }
}
