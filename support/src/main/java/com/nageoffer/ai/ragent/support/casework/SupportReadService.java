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

package com.nageoffer.ai.ragent.support.casework;

import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class SupportReadService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SupportAccessService access;

    public SupportReadService(NamedParameterJdbcTemplate jdbc, SupportAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Map<String, Object> caseContext(SupportRunScope scope) {
        SupportCase supportCase = access.requireScope(scope);
        return Map.of("caseId", supportCase.id(), "scenario", supportCase.scenario().name(),
                "resourceRef", supportCase.resourceId(), "description", supportCase.description());
    }

    public Map<String, Object> subscription(SupportRunScope scope) {
        requireScenario(scope, SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, payment_state, activation_state, version FROM support_subscription
                WHERE tenant_id=:tenant AND id=:resource
                """, resourceParams(scope), (rs, row) -> Map.<String, Object>of(
                "subscriptionRef", rs.getString("id"), "paymentState", rs.getString("payment_state"),
                "activationState", rs.getString("activation_state"), "version", rs.getLong("version")));
        if (rows.isEmpty()) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        return rows.get(0);
    }

    public Map<String, Object> subscriptionEvents(SupportRunScope scope, Instant from, Instant to, int limit) {
        requireScenario(scope, SupportCase.Scenario.SUBSCRIPTION_ACTIVATION);
        validateWindow(from, to, limit);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, event_type, occurred_at, safe_payload->>'errorCode' AS error_code
                FROM support_subscription_event WHERE tenant_id=:tenant AND subscription_id=:resource
                AND occurred_at>=:from AND occurred_at<=:to ORDER BY occurred_at DESC, id LIMIT :limit
                """, Map.of("tenant", scope.tenantId(), "resource", scope.resourceId(),
                "from", java.sql.Timestamp.from(from), "to", java.sql.Timestamp.from(to), "limit", limit + 1),
                (rs, row) -> Map.<String, Object>of("eventId", rs.getString("id"), "eventType", rs.getString("event_type"),
                        "occurredAt", rs.getTimestamp("occurred_at").toInstant().toString(),
                        "errorCode", safeErrorCode(rs.getString("error_code"))));
        return Map.of("items", rows.subList(0, Math.min(limit, rows.size())), "truncated", rows.size() > limit,
                "from", from.toString(), "to", to.toString());
    }

    private void requireScenario(SupportRunScope scope, SupportCase.Scenario expected) {
        access.requireScope(scope);
        if (scope.scenario() != expected) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
    }

    private Map<String, Object> resourceParams(SupportRunScope scope) {
        return Map.of("tenant", scope.tenantId(), "resource", scope.resourceId());
    }

    public static void validateWindow(Instant from, Instant to, int limit) {
        if (from == null || to == null || from.isAfter(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || to.isAfter(Instant.now().plusSeconds(60)) || limit < 1 || limit > 100)
            throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
    }

    public static String safeErrorCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : "UNCLASSIFIED";
    }
}
