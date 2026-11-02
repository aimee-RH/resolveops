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

package com.nageoffer.ai.ragent.support.access;

import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SupportAccessService {
    public enum Permission {
        READ(List.of("VIEWER", "OPERATOR", "APPROVER")),
        INVESTIGATE(List.of("OPERATOR", "APPROVER")), APPROVE(List.of("APPROVER"));
        private final List<String> roles;
        Permission(List<String> roles) { this.roles = roles; }
    }

    public static final RowMapper<SupportCase> CASE_MAPPER = (rs, row) -> new SupportCase(
            rs.getString("id"), rs.getString("tenant_id"), SupportCase.Scenario.valueOf(rs.getString("scenario")),
            rs.getString("resource_id"), rs.getString("description"), rs.getString("status"), rs.getLong("version"));
    private final NamedParameterJdbcTemplate jdbc;

    public SupportAccessService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SupportCase requireCase(String actorId, String caseId, Permission permission) {
        requireActor(actorId);
        if (caseId == null || caseId.isBlank() || caseId.length() > 64)
            throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        List<SupportCase> cases = jdbc.query("""
                SELECT c.* FROM support_case c WHERE c.id=:caseId AND EXISTS (
                  SELECT 1 FROM support_access_grant g
                  WHERE g.tenant_id=c.tenant_id AND g.user_id=:actorId AND g.role IN (:roles))
                """, Map.of("caseId", caseId, "actorId", actorId, "roles", permission.roles), CASE_MAPPER);
        if (cases.isEmpty()) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        return cases.get(0);
    }

    public List<SupportCase> listCases(String actorId) {
        requireActor(actorId);
        return jdbc.query("""
                SELECT c.* FROM support_case c WHERE EXISTS (
                  SELECT 1 FROM support_access_grant g WHERE g.tenant_id=c.tenant_id AND g.user_id=:actorId)
                ORDER BY c.id LIMIT 100
                """, Map.of("actorId", actorId), CASE_MAPPER);
    }

    public SupportCase requireScope(SupportRunScope scope) {
        if (scope == null) throw new SupportException(SupportException.Code.NOT_AUTHENTICATED);
        SupportCase supportCase = requireCase(scope.actorId(), scope.caseId(), Permission.INVESTIGATE);
        Integer matches = jdbc.queryForObject("""
                SELECT count(*) FROM support_run WHERE id=:runId AND actor_id=:actorId
                AND tenant_id=:tenantId AND case_id=:caseId AND status='RUNNING'
                """, Map.of("runId", scope.runId(), "actorId", scope.actorId(),
                "tenantId", scope.tenantId(), "caseId", scope.caseId()), Integer.class);
        if (matches == null || matches != 1 || !supportCase.tenantId().equals(scope.tenantId())
                || !supportCase.resourceId().equals(scope.resourceId()) || supportCase.scenario() != scope.scenario())
            throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        return supportCase;
    }

    public static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new SupportException(SupportException.Code.NOT_AUTHENTICATED);
    }
}
