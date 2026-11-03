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

package com.nageoffer.ai.ragent.support.run;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class SupportDiagnosisValidator {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("outcome", "facts", "hypotheses", "missingInformation",
            "recommendedAction", "runbookEvidenceIds");
    private final SupportAccessService access;
    private final SupportRunStore runs;
    private final NamedParameterJdbcTemplate jdbc;

    public SupportDiagnosisValidator(SupportAccessService access, SupportRunStore runs, NamedParameterJdbcTemplate jdbc) {
        this.access = access; this.runs = runs; this.jdbc = jdbc;
    }

    public Map<String, Object> validate(SupportRunScope scope, String raw) {
        access.requireScope(scope);
        if (raw == null || raw.length() > 16000) throw invalid();
        Map<String, Object> result;
        try { result = JSON.readValue(raw, new TypeReference<>() { }); }
        catch (Exception e) { throw invalid(); }
        if (result == null || !result.keySet().equals(FIELDS)) throw invalid();
        String outcome = text(result.get("outcome"), 40);
        if (!Set.of("PROPOSE_REPAIR", "NO_ACTION_NEEDED", "NEEDS_INFORMATION", "HANDOFF").contains(outcome)) throw invalid();
        var evidence = runs.evidence(scope.actorId(), scope.runId()).stream()
                .collect(Collectors.toMap(SupportRunStore.Evidence::id, e -> e));
        Set<String> cited = new HashSet<>();
        List<?> facts = list(result.get("facts"), 8);
        if (facts.isEmpty()) throw invalid();
        for (Object item : facts) {
            if (!(item instanceof Map<?, ?> fact) || !fact.keySet().equals(Set.of("text", "evidenceIds"))) throw invalid();
            text(fact.get("text"), 500);
            List<String> refs = strings(fact.get("evidenceIds"), 8, 64);
            if (refs.isEmpty() || !evidence.keySet().containsAll(refs)) throw invalid();
            cited.addAll(refs);
        }
        strings(result.get("hypotheses"), 5, 500);
        List<String> missing = strings(result.get("missingInformation"), 5, 300);
        List<String> manuals = strings(result.get("runbookEvidenceIds"), 5, 64);
        for (String id : manuals) {
            if (!evidence.containsKey(id) || !"RUNBOOK".equals(evidence.get(id).kind())) throw invalid();
        }
        var citedEvidence = evidence.values().stream().filter(e -> cited.contains(e.id())).toList();
        Predicate<SupportRunStore.Evidence> active = e -> "SUBSCRIPTION".equals(e.kind())
                && "ACTIVE".equals(e.snapshot().get("activationState"));
        if ("NO_ACTION_NEEDED".equals(outcome) && citedEvidence.stream().noneMatch(active)) throw invalid();
        if ("NEEDS_INFORMATION".equals(outcome) && missing.isEmpty()) throw invalid();
        if ("PROPOSE_REPAIR".equals(outcome)) {
            if (scope.scenario() != SupportCase.Scenario.SUBSCRIPTION_ACTIVATION || !missing.isEmpty()
                    || !(result.get("recommendedAction") instanceof Map<?, ?> action)
                    || !action.equals(Map.of("type", "RECONCILE_ACTIVATION"))) throw invalid();
            boolean paidPending = citedEvidence.stream().anyMatch(e -> "SUBSCRIPTION".equals(e.kind())
                    && "PAID".equals(e.snapshot().get("paymentState"))
                    && ("PENDING".equals(e.snapshot().get("activationState"))
                    || "FAILED".equals(e.snapshot().get("activationState"))));
            boolean timeline = citedEvidence.stream().anyMatch(e -> "EVENTS".equals(e.kind())
                    && Boolean.FALSE.equals(e.snapshot().get("truncated"))
                    && event(e, "PAYMENT_CONFIRMED") && event(e, "ACTIVATION_FAILED"));
            boolean policy = manuals.stream().map(evidence::get).anyMatch(this::approvedPolicy);
            if (!paidPending || !timeline || !policy) throw invalid();
        } else if (result.get("recommendedAction") != null) throw invalid();
        // Citation membership and preconditions are checked here; factual entailment still needs evaluation.
        access.requireScope(scope);
        return result;
    }

    private boolean approvedPolicy(SupportRunStore.Evidence evidence) {
        if (!Boolean.FALSE.equals(evidence.snapshot().get("truncated"))) return false;
        Object hash = evidence.snapshot().get("chunkHash");
        if (!(hash instanceof String value) || !value.matches("[a-f0-9]{64}")) return false;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM support_runbook_policy WHERE doc_id=:doc AND content_hash=:hash
                AND policy_code='ACTIVATION_RECONCILE_V1'
                """, Map.of("doc", evidence.sourceRef(), "hash", value), Integer.class);
        return count != null && count == 1;
    }

    private static boolean event(SupportRunStore.Evidence evidence, String type) {
        Object items = evidence.snapshot().get("items");
        return items instanceof List<?> values && values.stream().anyMatch(item ->
                item instanceof Map<?, ?> row && type.equals(row.get("eventType")));
    }

    private static List<?> list(Object value, int max) {
        if (!(value instanceof List<?> values) || values.size() > max) throw invalid();
        return values;
    }

    private static List<String> strings(Object value, int max, int length) {
        return list(value, max).stream().map(item -> text(item, length)).toList();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalid();
        return text;
    }

    private static SupportException invalid() { return new SupportException(SupportException.Code.INVALID_DIAGNOSIS); }
}
