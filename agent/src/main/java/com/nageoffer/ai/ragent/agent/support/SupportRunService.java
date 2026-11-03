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
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.SupportJson;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "support.investigation.enabled", havingValue = "true")
public class SupportRunService {
    private final SupportAccessService access;
    private final SupportRunStore runs;
    private final SupportAgentProvider agents;
    private final SupportDiagnosisValidator validator;
    private final Map<String, Running> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> new Thread(task, "resolveops-investigator"), new ThreadPoolExecutor.AbortPolicy());

    public SupportRunService(SupportAccessService access, SupportRunStore runs, SupportAgentProvider agents,
                             SupportDiagnosisValidator validator) {
        this.access = access; this.runs = runs; this.agents = agents; this.validator = validator;
    }

    public SupportRunStore.RunView submit(String actor, String caseId, String requestKey, String question) {
        var supportCase = access.requireCase(actor, caseId, SupportAccessService.Permission.INVESTIGATE);
        if (supportCase.scenario() != SupportCase.Scenario.SUBSCRIPTION_ACTIVATION)
            throw new SupportException(SupportException.Code.CONFIGURATION_REQUIRED);
        String input = question == null || question.isBlank() ? "Investigate this case using current evidence." : question.strip();
        if (input.length() > 2000) throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        var scope = runs.create(actor, caseId, requestKey, agents.snapshot(input));
        var state = runs.get(actor, scope.runId());
        if (!"PENDING".equals(state.status())) return state;
        var running = new Running(scope);
        if (active.putIfAbsent(scope.runId(), running) == null) {
            try { worker.execute(() -> execute(running, input)); }
            catch (RejectedExecutionException e) {
                active.remove(scope.runId(), running);
                settle(scope, "FAILED", Map.of("error", "CAPACITY_EXCEEDED"));
                throw new SupportException(SupportException.Code.UNAVAILABLE);
            }
        }
        return runs.get(actor, scope.runId());
    }

    public SupportRunStore.RunView cancel(String actor, String runId) {
        var scope = runs.scope(actor, runId);
        var running = active.get(runId);
        settle(scope, "INTERRUPTED", Map.of("error", "CANCELLED"));
        if (running != null) running.stop();
        return runs.get(actor, runId);
    }

    private void execute(Running running, String question) {
        var scope = running.scope;
        try {
            var pending = runs.get(scope.actorId(), scope.runId());
            Duration remaining = Duration.between(Instant.now(), pending.startedAt().plusSeconds(120));
            if (remaining.isNegative() || remaining.isZero()) {
                settle(scope, "INTERRUPTED", Map.of("error", "QUEUE_TIMEOUT"));
                return;
            }
            if (!runs.start(scope)) return;
            if (running.budget.cancelled()) return;
            running.agent = agents.create(scope);
            runs.event(scope, "INVESTIGATION_STARTED", Map.of());
            var message = Msg.builder().role(MsgRole.USER)
                    .content(TextBlock.builder().text(question).build()).build();
            running.agent.streamEvents(message, running.context)
                    .doOnNext(event -> {
                        if (event instanceof ToolResultEndEvent end && end.getState() != ToolResultState.SUCCESS)
                            runs.event(scope, "TOOL_FAILED", Map.of("tool", end.getToolCallName(), "error", "SDK_TOOL_ERROR"));
                    })
                    .filter(AgentResultEvent.class::isInstance).single()
                    .subscribeOn(Schedulers.boundedElastic())
                    .cancelOn(Schedulers.boundedElastic())
                    .takeUntilOther(running.cancelled.asMono().publishOn(Schedulers.boundedElastic()))
                    .block(remaining.compareTo(Duration.ofSeconds(120)) > 0 ? Duration.ofSeconds(120) : remaining);
            if (running.budget.cancelled()) return;
            var observed = runs.evidence(scope.actorId(), scope.runId());
            String data = SupportJson.write(Map.of("evidence", observed));
            Duration correctionTime = Duration.between(Instant.now(), pending.startedAt().plusSeconds(120));
            if (observed.isEmpty() || data.getBytes(StandardCharsets.UTF_8).length > 5000
                    || correctionTime.isNegative() || correctionTime.isZero())
                throw new SupportException(SupportException.Code.INVALID_DIAGNOSIS);
            access.requireScope(scope);
            runs.event(scope, "DIAGNOSIS_STARTED", Map.of("attempt", 1));
            try (var formatter = agents.formatter()) {
                var correction = Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(data).build()).build();
                var formatted = formatter.call(List.of(correction), SupportAgentProvider.diagnosisSchema(observed), running.context)
                        .subscribeOn(Schedulers.boundedElastic())
                        .cancelOn(Schedulers.boundedElastic())
                        .takeUntilOther(running.cancelled.asMono().publishOn(Schedulers.boundedElastic())).block(correctionTime);
                if (running.budget.cancelled()) return;
                String corrected = formatted == null ? null : formatted.hasStructuredData()
                        ? SupportJson.write(formatted.getStructuredData(false)) : formatted.getTextContent();
                var diagnosis = validator.validate(scope, corrected);
                settle(scope, "COMPLETED", diagnosis);
            }
        } catch (Exception error) {
            if (!running.budget.cancelled()) {
                String code = error instanceof SupportException e ? e.code().name() : "INVESTIGATION_FAILED";
                settle(scope, "FAILED", Map.of("error", code));
            }
        } finally {
            running.stop();
            if (running.agent != null) running.agent.close();
            active.remove(scope.runId(), running);
        }
    }

    private void settle(SupportRunScope scope, String status, Map<String, Object> diagnosis) {
        if (runs.finish(scope, status, diagnosis)) runs.event(scope, "INVESTIGATION_" + status, Map.of("status", status));
    }

    @PreDestroy
    public void close() {
        active.values().forEach(running -> {
            running.stop();
            settle(running.scope, "INTERRUPTED", Map.of("error", "SERVER_SHUTDOWN"));
        });
        worker.shutdownNow();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Running {
        final SupportRunScope scope;
        final SupportBudget budget = new SupportBudget();
        final Sinks.Empty<Void> cancelled = Sinks.empty();
        final RuntimeContext context;
        volatile ReActAgent agent;
        Running(SupportRunScope scope) {
            this.scope = scope;
            context = RuntimeContext.builder().userId(scope.actorId()).sessionId("support:" + scope.runId())
                    .put(SupportRunScope.class, scope).put(SupportBudget.class, budget).build();
        }
        void stop() {
            budget.cancel();
            cancelled.tryEmitEmpty();
        }
    }
}
