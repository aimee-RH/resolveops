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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentEngineConfiguration;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.embedding.OllamaEmbeddingClient;
import com.nageoffer.ai.ragent.infra.embedding.RoutingEmbeddingService;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
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
import okhttp3.OkHttpClient;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
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

@EnabledIfEnvironmentVariable(named = "RESOLVEOPS_INVESTIGATION_TEST", matches = "1")
class SupportInvestigationIntegrationTest {
    private static final String SOP = "Subscription activation reconciliation: if payment is confirmed and activation failed "
            + "for a paid pending subscription, recommend RECONCILE_ACTIVATION for operator review.";
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
        properties.setProperty("currentSchema", schema + ",public");
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
        return realService(null);
    }

    private SupportRunService realService(RunbookEvidenceSearchService runbooks) {
        var binder = LocalModelConfigurationTest.configuration();
        var properties = binder.bind("agent", AgentProperties.class).get();
        properties.getChat().setModel(System.getenv().getOrDefault("RESOLVEOPS_INVESTIGATION_MODEL", properties.getChat().getModel()));
        var model = spy(new AgentEngineConfiguration(properties, binder.bind("ai", AIModelProperties.class).get()).agentChatModel());
        doAnswer(invocation -> {
            List<ToolSchema> tools = invocation.getArgument(1);
            protocol.add(Map.of("requestedTools", tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList()));
            return invocation.callRealMethod();
        }).when(model).stream(anyList(), anyList(), any(GenerateOptions.class));
        var beans = new StaticListableBeanFactory();
        if (runbooks != null) beans.addBean("runbooks", runbooks);
        var agent = new SupportAgentProvider(model, properties, new SupportReadService(jdbc, access), runs,
                beans.getBeanProvider(RunbookEvidenceSearchService.class));
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
    @EnabledIfEnvironmentVariable(named = "RESOLVEOPS_RUNBOOK_TEST", matches = "1")
    void indexedRunbookRequiresRegisteredContentBeforeModelCanSuggestRepair() throws Exception {
        var runbooks = indexedRunbooks();
        var top = runbooks.search("subscription activation reconciliation", 1);
        assertThat(top).extracting(RunbookEvidenceSearchService.ChunkEvidence::docId).containsExactly("sop-activation");
        service = realService(runbooks);
        var unregistered = await(service.submit("operator", "case-a", "indexed-unregistered", null).id());
        assertThat(unregistered.status()).as(SupportJson.write(unregistered)).isEqualTo("COMPLETED");
        assertThat(unregistered.diagnosis()).containsEntry("outcome", "HANDOFF").containsEntry("recommendedAction", null);
        var unregisteredEvidence = runs.evidence("operator", unregistered.id());
        assertThat(unregisteredEvidence).anySatisfy(e -> assertThat(e.sourceRef()).isEqualTo("sop-activation"));
        assertThat(SupportJson.write(unregisteredEvidence)).doesNotContain("restricted-sop");
        recordRunbookProbe("unregistered", unregistered);

        sql.update("""
                INSERT INTO support_runbook_policy (doc_id, content_hash, policy_code)
                VALUES ('sop-activation', encode(sha256(convert_to(?, 'UTF8')), 'hex'), 'ACTIVATION_RECONCILE_V1')
                """, SOP);
        var registered = await(service.submit("operator", "case-a", "indexed-registered", null).id());
        assertThat(registered.status()).as(SupportJson.write(registered)).isEqualTo("COMPLETED");
        assertThat(registered.diagnosis()).containsEntry("outcome", "PROPOSE_REPAIR")
                .containsEntry("recommendedAction", Map.of("type", "RECONCILE_ACTIVATION"));
        var evidence = runs.evidence("operator", registered.id());
        assertThat(SupportJson.write(evidence)).doesNotContain("restricted-sop");
        assertThat(evidence).anySatisfy(e -> {
            assertThat(e.sourceRef()).isEqualTo("sop-activation");
            assertThat(registered.diagnosis().get("runbookEvidenceIds")).asList().contains(e.id());
        });
        assertThat(sql.queryForObject("SELECT activation_state FROM support_subscription WHERE id='sub-a'", String.class))
                .isEqualTo("PENDING");
        assertThat(sql.queryForObject("SELECT count(*) FROM support_action_operation", Integer.class)).isZero();
        UserContext.set(LoginUser.builder().userId("operator").build());
        mvc().perform(get("/support/runs/" + registered.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.diagnosis.outcome").value("PROPOSE_REPAIR"));
        recordRunbookProbe("registered", registered);
    }

    private RunbookEvidenceSearchService indexedRunbooks() throws Exception {
        var binder = LocalModelConfigurationTest.configuration("local-runbooks");
        var ai = binder.bind("ai", AIModelProperties.class).get();
        int dimension = binder.bind("rag.default.dimension", Integer.class).get();
        String schemaSql = Files.readString(Path.of("../resources/database/schema_pg.sql"));
        new ResourceDatabasePopulator(new ByteArrayResource(schemaSql.replace("vector(1536)", "vector(" + dimension + ")")
                .getBytes(StandardCharsets.UTF_8))).execute(sql.getDataSource());

        var health = new ModelHealthStore(ai);
        var embedding = new RoutingEmbeddingService(new ModelSelector(ai, health), new ModelRoutingExecutor(health),
                List.of(new OllamaEmbeddingClient(new OkHttpClient())));
        var embedder = new ChunkEmbeddingService(embedding);
        var store = new PgVectorStoreService(sql, new ObjectMapper());
        indexRunbook(embedder, store, dimension, ai.getEmbedding().getDefaultModel(), "support-sops", "sop-activation", SOP);
        indexRunbook(embedder, store, dimension, ai.getEmbedding().getDefaultModel(), "support-sops", "sop-invoices",
                "Invoice export and tax receipt formatting for finance agents.");
        indexRunbook(embedder, store, dimension, ai.getEmbedding().getDefaultModel(), "restricted", "restricted-sop",
                "Subscription activation reconciliation");

        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("runbook-probe", new SpringManagedTransactionFactory(), sql.getDataSource()));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(KnowledgeChunkMapper.class);
        config.addMapper(KnowledgeDocumentMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var metadata = new ChunkMetadataResolver(session.getMapper(KnowledgeChunkMapper.class),
                session.getMapper(KnowledgeDocumentMapper.class));
        return new RunbookEvidenceSearchService(new PgVectorRetrieverService(sql, embedding), metadata, List.of("support-sops"));
    }

    private void indexRunbook(ChunkEmbeddingService embedder, PgVectorStoreService store, int dimension, String model,
                              String collection, String id, String text) {
        String chunkId = id + "-1";
        sql.update("""
                INSERT INTO t_knowledge_document (id,kb_id,doc_name,file_url,file_type,created_by)
                VALUES (?, ?, ?, 'fixture:runbook', 'txt', 'operator')
                """, id, collection, id);
        sql.update("""
                INSERT INTO t_knowledge_chunk (id,kb_id,doc_id,chunk_index,content,created_by)
                VALUES (?, ?, ?, 0, ?, 'operator')
                """, chunkId, collection, id, text);
        store.indexDocumentChunks(collection, id, embedder.embed(List.of(new Chunk(chunkId, 0, text, text, null)),
                new VectorTarget(collection, model, dimension)));
    }

    private void recordRunbookProbe(String name, SupportRunStore.RunView run) throws Exception {
        Files.writeString(Path.of("target/investigation-indexed-" + name + ".json"), SupportJson.write(Map.of("run", run,
                "evidence", runs.evidence("operator", run.id()), "events", runs.events("operator", run.id(), 0))));
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
