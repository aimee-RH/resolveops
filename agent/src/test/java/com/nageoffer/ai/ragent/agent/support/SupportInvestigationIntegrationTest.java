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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.service.RunbookEvidenceSearchService;
import com.nageoffer.ai.ragent.support.SupportJson;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.casework.SupportReadService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import com.nageoffer.ai.ragent.support.web.SupportCaseController;
import com.nageoffer.ai.ragent.support.web.SupportExceptionAdvice;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real PostgreSQL + real generation; no runbook index is supplied in this increment.
@EnabledIfEnvironmentVariable(named = "RESOLVEOPS_INVESTIGATION_TEST", matches = "1")
class SupportInvestigationIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private String schema;
    private SupportAccessService access;
    private SupportRunStore runs;
    private SupportRunService service;
    private final List<Map<String, Object>> protocol = Collections.synchronizedList(new ArrayList<>());
    private final Hook protocolHook = new Hook() {
        @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (event instanceof PostReasoningEvent post) {
                var msg = post.getReasoningMessage();
                protocol.add(Map.of("reason", String.valueOf(msg.getGenerateReason()),
                        "textCharacters", msg.getTextContent() == null ? 0 : msg.getTextContent().length(),
                        "llamaAnswer", System.getenv().getOrDefault("RESOLVEOPS_INVESTIGATION_MODEL", "").contains("llama")
                                && msg.getTextContent() != null ? msg.getTextContent() : "",
                        "structured", msg.hasStructuredData(),
                        "toolNames", msg.getContentBlocks(ToolUseBlock.class).stream().map(ToolUseBlock::getName).toList()));
            }
            return Mono.just(event);
        }
    };

    @BeforeEach
    void prepare() {
        AgentBase.addSystemHook(protocolHook);
        String url = System.getenv("RESOLVEOPS_TEST_DB_URL");
        assertThat(url).as("Use a dedicated real PostgreSQL test endpoint").isNotBlank();
        var source = new DriverManagerDataSource(url, "postgres", "postgres");
        admin = new JdbcTemplate(source);
        schema = "resolveops_investigation_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        new ResourceDatabasePopulator(new ClassPathResource("db/261102_support_domain.sql")).execute(source);
        sql = new JdbcTemplate(source);
        jdbc = new NamedParameterJdbcTemplate(source);
        sql.update("INSERT INTO support_tenant VALUES ('tenant-a','Synthetic customer'),('tenant-b','Private other tenant')");
        sql.update("INSERT INTO support_access_grant VALUES ('operator','tenant-a','OPERATOR'),('viewer','tenant-a','VIEWER'),('other','tenant-b','OPERATOR')");
        sql.update("INSERT INTO support_subscription VALUES ('sub-a','tenant-a','PAID','PENDING',1),('sub-c','tenant-a','PAID','ACTIVE',1),('sub-b','tenant-b','PAID','PENDING',1)");
        sql.update("INSERT INTO support_case(id,tenant_id,scenario,resource_id,description) VALUES ('case-a','tenant-a','SUBSCRIPTION_ACTIVATION','sub-a','Customer paid but activation is pending'),('case-c','tenant-a','SUBSCRIPTION_ACTIVATION','sub-c','Customer asks whether activation needs repair'),('case-b','tenant-b','SUBSCRIPTION_ACTIVATION','sub-b','PRIVATE_OTHER_TENANT')");
        sql.update("INSERT INTO support_subscription_event(id,tenant_id,subscription_id,event_type,occurred_at) VALUES ('payment-a','tenant-a','sub-a','PAYMENT_CONFIRMED',CURRENT_TIMESTAMP - INTERVAL '2 hours'),('failure-a','tenant-a','sub-a','ACTIVATION_FAILED',CURRENT_TIMESTAMP - INTERVAL '1 hour')");
        access = new SupportAccessService(jdbc);
        runs = new SupportRunStore(jdbc, access);
    }

    private SupportRunService realService() {
        var binder = LocalModelConfigurationTest.configuration();
        var properties = binder.bind("agent", AgentProperties.class).get();
        properties.getChat().setModel(System.getenv().getOrDefault("RESOLVEOPS_INVESTIGATION_MODEL", properties.getChat().getModel()));
        var model = spy(new AgentEngineConfiguration(properties, binder.bind("ai", AIModelProperties.class).get()).agentChatModel());
        doAnswer(invocation -> {
            List<ToolSchema> tools = invocation.getArgument(1);
            protocol.add(Map.of("requestedTools", tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList()));
            return invocation.callRealMethod();
        }).when(model).stream(anyList(), anyList(), any(GenerateOptions.class));
        var agent = new SupportAgentProvider(model, properties, new SupportReadService(jdbc, access), runs,
                new StaticListableBeanFactory().getBeanProvider(RunbookEvidenceSearchService.class));
        return new SupportRunService(access, runs, agent, new SupportDiagnosisValidator(access, runs, jdbc));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new SupportRunController(service), new SupportCaseController(access, runs, true))
                .setControllerAdvice(new SupportExceptionAdvice()).build();
    }

    @AfterEach
    void clean() throws Exception {
        UserContext.clear();
        if (service != null) service.close();
        AgentBase.removeSystemHook(protocolHook);
        Files.writeString(Path.of("target/investigation-protocol-" + schema + ".json"), SupportJson.write(protocol));
        if (schema != null) Files.writeString(Path.of("target/investigation-database-" + schema + ".json"), SupportJson.write(Map.of(
                "runs", sql.queryForList("SELECT id,status,diagnosis::text FROM support_run"),
                "evidence", sql.queryForList("SELECT source_kind,snapshot::text FROM support_evidence"),
                "events", sql.queryForList("SELECT type,safe_payload::text FROM support_run_event ORDER BY sequence"))));
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void realInvestigationPersistsHandoffAndReplayWithoutExposingOtherTenantOrWritingBusinessState() throws Exception {
        service = realService();
        var mvc = mvc();
        mvc.perform(post("/support/cases/case-a/runs").header("X-User-Id", "operator")
                .contentType("application/json").content("{\"requestKey\":\"native\"}" )).andExpect(status().isUnauthorized());
        UserContext.set(LoginUser.builder().userId("viewer").build());
        mvc.perform(post("/support/cases/case-a/runs").contentType("application/json").content("{\"requestKey\":\"native\"}"))
                .andExpect(status().isNotFound());
        UserContext.set(LoginUser.builder().userId("operator").build());
        var first = mvc.perform(post("/support/cases/case-a/runs").contentType("application/json")
                .content("{\"requestKey\":\"native\"}")).andExpect(status().isOk()).andReturn();
        String runId = JSON.parseObject(first.getResponse().getContentAsString()).getJSONObject("data").getString("id");
        mvc.perform(post("/support/cases/case-a/runs").contentType("application/json").content("{\"requestKey\":\"native\"}"))
                .andExpect(jsonPath("$.data.id").value(runId));
        var result = await(runId);
        assertThat(result.status()).as(SupportJson.write(result)).isEqualTo("COMPLETED");
        assertThat(result.diagnosis().get("outcome")).isEqualTo("HANDOFF");
        var evidence = runs.evidence("operator", runId);
        assertThat(evidence).isNotEmpty();
        assertThat(SupportJson.write(evidence)).doesNotContain("PRIVATE_OTHER_TENANT");
        assertThat(runs.events("operator", runId, 0)).anySatisfy(event -> assertThat(event.get("type")).isEqualTo("TOOL_FAILED"));
        assertThat(sql.queryForObject("SELECT count(*) FROM support_run", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT activation_state FROM support_subscription WHERE id='sub-a'", String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("SELECT count(*) FROM support_action_operation", Integer.class)).isZero();
        UserContext.set(LoginUser.builder().userId("other").build());
        mvc.perform(get("/support/runs/" + runId)).andExpect(status().isNotFound());
        Files.writeString(Path.of("target/investigation-handoff.json"), SupportJson.write(Map.of("run", result,
                "evidence", evidence, "events", runs.events("operator", runId, 0), "limits", "No live runbook index; trusted test login context")));
    }

    @Test
    void realInvestigationRecognizesAlreadyActiveSubscription() throws Exception {
        service = realService();
        var run = service.submit("operator", "case-c", "active", null);
        var result = await(run.id());
        assertThat(result.status()).as(SupportJson.write(result)).isEqualTo("COMPLETED");
        assertThat(result.diagnosis().get("outcome")).isEqualTo("NO_ACTION_NEEDED");
        Files.writeString(Path.of("target/investigation-active.json"), SupportJson.write(Map.of("run", result,
                "evidence", runs.evidence("operator", run.id()), "limits", "No live runbook index; direct trusted service actor")));
    }

    @Test
    void cancellationStopsAnInFlightStreamAndCannotBeOverwritten() throws Exception {
        var provider = mock(SupportAgentProvider.class);
        var agent = mock(ReActAgent.class);
        when(provider.snapshot(any())).thenReturn(Map.of("mode", "support-investigation-v1"));
        when(provider.create(any())).thenReturn(agent);
        var started = new CountDownLatch(1);
        var disposed = new CountDownLatch(1);
        when(agent.streamEvents(any(Msg.class), any()))
                .thenReturn(Flux.<AgentEvent>never().doOnSubscribe(s -> started.countDown()).doFinally(s -> disposed.countDown()));
        service = new SupportRunService(access, runs, provider, new SupportDiagnosisValidator(access, runs, jdbc));
        var run = service.submit("operator", "case-a", "cancel", null);
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.cancel("other", run.id())).hasMessage("NOT_FOUND_OR_FORBIDDEN");
        assertThat(service.cancel("operator", run.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(disposed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(service.submit("operator", "case-a", "cancel", null).status()).isEqualTo("INTERRUPTED");
        assertThat(sql.queryForObject("SELECT count(*) FROM support_run", Integer.class)).isEqualTo(1);
        assertThat(runs.get("operator", run.id()).diagnosis()).containsEntry("error", "CANCELLED");
    }

    private SupportRunStore.RunView await(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(140);
        var run = runs.get("operator", runId);
        while (SetOfOngoing.contains(run.status()) && System.nanoTime() < deadline) {
            Thread.sleep(250);
            run = runs.get("operator", runId);
        }
        return run;
    }
    private static final java.util.Set<String> SetOfOngoing = java.util.Set.of("PENDING", "RUNNING");
}
