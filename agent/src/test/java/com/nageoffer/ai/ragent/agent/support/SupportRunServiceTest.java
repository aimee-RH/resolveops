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
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.run.SupportDiagnosisValidator;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import io.agentscope.core.ReActAgent;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportRunServiceTest {
    private final SupportAccessService access = mock(SupportAccessService.class);
    private final SupportRunStore runs = mock(SupportRunStore.class);
    private final SupportAgentProvider provider = mock(SupportAgentProvider.class);
    private final SupportDiagnosisValidator validator = mock(SupportDiagnosisValidator.class);
    private final ReActAgent agent = mock(ReActAgent.class);
    private final ReActAgent formatter = mock(ReActAgent.class);
    private final SupportRunScope scope = new SupportRunScope("run", "actor", "tenant", "case", "sub", SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
    private final AtomicReference<String> status = new AtomicReference<>("PENDING");
    private final AtomicReference<Map<String, Object>> diagnosis = new AtomicReference<>(Map.of());
    private final CountDownLatch terminal = new CountDownLatch(1);
    private SupportRunService service;

    @BeforeEach
    void prepare() {
        when(access.requireCase("actor", "case", SupportAccessService.Permission.INVESTIGATE))
                .thenReturn(new SupportCase("case", "tenant", scope.scenario(), "sub", "Synthetic", "OPEN", 1));
        when(provider.snapshot(any())).thenReturn(Map.of("mode", "support-investigation-v1"));
        when(provider.create(scope)).thenReturn(agent);
        when(provider.formatter()).thenReturn(formatter);
        when(formatter.call(anyList(), any(JsonNode.class), any())).thenReturn(Mono.just(
                Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("formatted").build()).build()));
        when(validator.validate(scope, "formatted")).thenReturn(Map.of("outcome", "HANDOFF"));
        when(runs.create(eq("actor"), eq("case"), anyString(), anyMap())).thenReturn(scope);
        when(runs.get("actor", "run")).thenAnswer(call -> new SupportRunStore.RunView("run", "case", "actor", status.get(),
                diagnosis.get(), Map.of(), Instant.now(), null));
        when(runs.scope("actor", "run")).thenReturn(scope);
        when(runs.evidence("actor", "run")).thenReturn(java.util.List.of(new SupportRunStore.Evidence(
                "evidence", "CASE", "case", null, "hash", Instant.now(), Map.of("description", "Synthetic"))));
        when(runs.start(scope)).thenAnswer(call -> status.compareAndSet("PENDING", "RUNNING"));
        when(runs.finish(eq(scope), anyString(), anyMap())).thenAnswer(call -> {
            String previous = status.get();
            if (!java.util.Set.of("PENDING", "RUNNING").contains(previous)) return false;
            diagnosis.set(call.getArgument(2));
            boolean changed = status.compareAndSet(previous, call.getArgument(1));
            if (changed) terminal.countDown();
            return changed;
        });
        service = new SupportRunService(access, runs, provider, validator);
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void duplicateSubmissionDoesNotRestartModelAndCancellationDisposesStream() throws Exception {
        var started = new CountDownLatch(1);
        var disposed = new CountDownLatch(1);
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.<AgentEvent>never()
                .doOnSubscribe(s -> started.countDown()).doFinally(s -> disposed.countDown()));
        service.submit("actor", "case", "key", null);
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(service.submit("actor", "case", "key", null).status()).isEqualTo("RUNNING");
        assertThat(service.cancel("actor", "run").status()).isEqualTo("INTERRUPTED");
        assertThat(disposed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(diagnosis.get()).containsEntry("error", "CANCELLED");
        verify(provider, times(1)).create(scope);
        verifyNoInteractions(validator);
    }

    @Test
    void modelFailureIsPersistedWithoutExceptionSecrets() throws Exception {
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.error(new IllegalStateException("jdbc password=SECRET")));
        service.submit("actor", "case", "key", null);
        assertThat(terminal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(status.get()).isEqualTo("FAILED");
        assertThat(diagnosis.get()).containsExactlyEntriesOf(Map.of("error", "INVESTIGATION_FAILED"));
    }

    @Test
    void invalidDiagnosisCannotBeReportedAsCompleted() throws Exception {
        var result = Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("untrusted output").build()).build();
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.just(new AgentResultEvent(result)));
        when(validator.validate(scope, "formatted")).thenThrow(new SupportException(SupportException.Code.INVALID_DIAGNOSIS));
        service.submit("actor", "case", "key", null);
        assertThat(terminal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(status.get()).isEqualTo("FAILED");
        assertThat(diagnosis.get()).containsExactlyEntriesOf(Map.of("error", "INVALID_DIAGNOSIS"));
    }

    @Test
    void expiredQueuedWorkNeverCallsTheModel() throws Exception {
        when(runs.get("actor", "run")).thenReturn(new SupportRunStore.RunView("run", "case", "actor", "PENDING",
                Map.of(), Map.of(), Instant.now().minusSeconds(121), null));
        service.submit("actor", "case", "key", null);
        assertThat(terminal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(diagnosis.get()).containsExactlyEntriesOf(Map.of("error", "QUEUE_TIMEOUT"));
        verify(provider, never()).create(any());
        verifyNoInteractions(agent);
    }

    @Test
    void finalizerSeesObservedEvidenceAndNeverTheUntrustedDraft() throws Exception {
        var result = Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("UNTRUSTED_DRAFT").build()).build();
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.just(new AgentResultEvent(result)));
        service.submit("actor", "case", "key", null);
        assertThat(terminal.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(status.get()).isEqualTo("COMPLETED");
        verify(formatter).call(argThat(messages -> messages.size() == 1
                && messages.get(0).getTextContent().contains("evidence")
                && !messages.get(0).getTextContent().contains("UNTRUSTED_DRAFT")), any(JsonNode.class), any());
    }

    @Test
    void blockingSubscriptionCannotPreventDeadlineFromSettlingRun() throws Exception {
        var release = new CountDownLatch(1);
        when(runs.get("actor", "run")).thenAnswer(call -> new SupportRunStore.RunView("run", "case", "actor", status.get(),
                diagnosis.get(), Map.of(), Instant.now().minusSeconds(119), null));
        when(agent.streamEvents(any(Msg.class), any())).thenReturn(Flux.defer(() -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Flux.never();
        }));
        try {
            service.submit("actor", "case", "key", null);
            assertThat(terminal.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(status.get()).isEqualTo("FAILED");
        } finally { release.countDown(); }
    }
}
