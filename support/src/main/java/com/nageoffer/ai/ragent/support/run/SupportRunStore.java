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

import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.SupportJson;
import com.nageoffer.ai.ragent.support.SupportRunScope;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SupportRunStore {
    public record RunView(String id, String caseId, String actorId, String status, Map<String, Object> diagnosis,
                          Map<String, Object> config, Instant startedAt, Instant finishedAt) { }
    public record Evidence(String id, String kind, String sourceRef, String version, String hash,
                           Instant observedAt, Map<String, Object> snapshot) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final SupportAccessService access;

    public SupportRunStore(NamedParameterJdbcTemplate jdbc, SupportAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public SupportRunScope create(String actor, String caseId, String requestKey, Map<String, Object> config) {
        SupportCase supportCase = access.requireCase(actor, caseId, SupportAccessService.Permission.INVESTIGATE);
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{1,100}") || config == null
                || !Set.of("model", "promptVersion", "toolVersion", "question", "mode").containsAll(config.keySet()))
            throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        String snapshot = SupportJson.write(config);
        if (snapshot.length() > 12000) throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        String hash = SupportJson.hash(snapshot);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO support_run(id, tenant_id, case_id, actor_id, session_id, status, config_snapshot, request_key, request_hash)
                VALUES (:id, :tenant, :caseId, :actor, :session, 'PENDING', CAST(:config AS jsonb), :key, :hash)
                ON CONFLICT(actor_id, case_id, request_key) DO NOTHING
                """, Map.of("id", id, "tenant", supportCase.tenantId(), "caseId", caseId, "actor", actor,
                "session", "support:" + id, "config", snapshot, "key", requestKey, "hash", hash));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, request_hash FROM support_run WHERE actor_id=:actor AND case_id=:caseId AND request_key=:key
                """, Map.of("actor", actor, "caseId", caseId, "key", requestKey));
        if (!hash.equals(rows.get(0).get("request_hash"))) throw new SupportException(SupportException.Code.CONFLICT);
        return scope(actor, (String) rows.get(0).get("id"));
    }

    public SupportRunScope scope(String actor, String runId) {
        RunView run = get(actor, runId);
        if (!actor.equals(run.actorId())) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        SupportCase supportCase = access.requireCase(actor, run.caseId(), SupportAccessService.Permission.INVESTIGATE);
        return new SupportRunScope(runId, actor, supportCase.tenantId(), supportCase.id(), supportCase.resourceId(), supportCase.scenario());
    }

    public boolean start(SupportRunScope scope) {
        requireIdentity(scope);
        access.requireCase(scope.actorId(), scope.caseId(), SupportAccessService.Permission.INVESTIGATE);
        return jdbc.update("UPDATE support_run SET status='RUNNING' WHERE id=:id AND status='PENDING'", Map.of("id", scope.runId())) == 1;
    }

    public boolean finish(SupportRunScope scope, String status, Map<String, Object> diagnosis) {
        requireIdentity(scope);
        if (!Set.of("COMPLETED", "FAILED", "INTERRUPTED").contains(status)) throw new IllegalArgumentException("Invalid terminal status");
        return jdbc.update("""
                UPDATE support_run SET status=:status, diagnosis=CAST(:diagnosis AS jsonb), finished_at=CURRENT_TIMESTAMP
                WHERE id=:id AND status IN ('PENDING', 'RUNNING')
                """, Map.of("id", scope.runId(), "status", status, "diagnosis", SupportJson.write(diagnosis))) == 1;
    }

    public RunView get(String actor, String runId) {
        SupportAccessService.requireActor(actor);
        List<RunView> rows = jdbc.query("SELECT * FROM support_run WHERE id=:id", Map.of("id", runId), (rs, row) -> {
            Timestamp finished = rs.getTimestamp("finished_at");
            return new RunView(rs.getString("id"), rs.getString("case_id"), rs.getString("actor_id"), rs.getString("status"),
                    SupportJson.read(rs.getString("diagnosis")), SupportJson.read(rs.getString("config_snapshot")),
                    rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant());
        });
        if (rows.isEmpty()) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        access.requireCase(actor, rows.get(0).caseId(), SupportAccessService.Permission.READ);
        return rows.get(0);
    }

    public List<RunView> forCase(String actor, String caseId) {
        access.requireCase(actor, caseId, SupportAccessService.Permission.READ);
        return jdbc.query("SELECT id FROM support_run WHERE case_id=:caseId ORDER BY started_at DESC LIMIT 50",
                Map.of("caseId", caseId), (rs, row) -> rs.getString("id")).stream().map(id -> get(actor, id)).toList();
    }

    public Evidence record(SupportRunScope scope, String kind, String ref, String version, Map<String, Object> snapshot) {
        access.requireScope(scope);
        String content = SupportJson.write(snapshot);
        if (content.length() > 65536) throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        String id = UUID.randomUUID().toString();
        String hash = SupportJson.hash(content);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO support_evidence(id, tenant_id, run_id, source_kind, source_ref, resource_version, content_hash, observed_at, snapshot)
                VALUES (:id, :tenant, :run, :kind, :ref, :version, :hash, :at, CAST(:snapshot AS jsonb))
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id", id)
                .addValue("tenant", scope.tenantId()).addValue("run", scope.runId()).addValue("kind", kind)
                .addValue("ref", ref).addValue("version", version).addValue("hash", hash)
                .addValue("at", Timestamp.from(now)).addValue("snapshot", content));
        return new Evidence(id, kind, ref, version, hash, now, snapshot);
    }

    public Evidence evidence(String actor, String runId, String evidenceId) {
        get(actor, runId);
        List<Evidence> rows = jdbc.query("SELECT * FROM support_evidence WHERE run_id=:run AND id=:id",
                Map.of("run", runId, "id", evidenceId), (rs, row) -> new Evidence(rs.getString("id"), rs.getString("source_kind"),
                        rs.getString("source_ref"), rs.getString("resource_version"), rs.getString("content_hash"),
                        rs.getTimestamp("observed_at").toInstant(), SupportJson.read(rs.getString("snapshot"))));
        if (rows.isEmpty()) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
        return rows.get(0);
    }

    public List<Evidence> evidence(String actor, String runId) {
        get(actor, runId);
        return jdbc.query("SELECT id FROM support_evidence WHERE run_id=:run ORDER BY observed_at, id LIMIT 100",
                Map.of("run", runId), (rs, row) -> rs.getString("id")).stream().map(id -> evidence(actor, runId, id)).toList();
    }

    public void event(SupportRunScope scope, String type, Map<String, Object> payload) {
        requireIdentity(scope);
        String safe = SupportJson.write(payload);
        if (safe.length() > 65536) throw new IllegalArgumentException("Support event too large");
        jdbc.update("INSERT INTO support_run_event(run_id, type, safe_payload) VALUES (:id, :type, CAST(:payload AS jsonb))",
                Map.of("id", scope.runId(), "type", type, "payload", safe));
    }

    public List<Map<String, Object>> events(String actor, String runId, long after) {
        get(actor, runId);
        if (after < 0) throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        return jdbc.query("SELECT sequence, type, safe_payload FROM support_run_event WHERE run_id=:id AND sequence>:after ORDER BY sequence LIMIT 100",
                Map.of("id", runId, "after", after), (rs, row) -> Map.of("sequence", rs.getLong("sequence"),
                        "type", rs.getString("type"), "payload", SupportJson.read(rs.getString("safe_payload"))));
    }

    private void requireIdentity(SupportRunScope scope) {
        Integer matches = jdbc.queryForObject("SELECT count(*) FROM support_run r JOIN support_case c ON c.id=r.case_id AND c.tenant_id=r.tenant_id WHERE r.id=:run AND r.actor_id=:actor AND c.id=:caseId AND c.tenant_id=:tenant AND c.resource_id=:resource AND c.scenario=:scenario",
                Map.of("run", scope.runId(), "actor", scope.actorId(), "caseId", scope.caseId(), "tenant", scope.tenantId(),
                        "resource", scope.resourceId(), "scenario", scope.scenario().name()), Integer.class);
        if (matches == null || matches != 1) throw new SupportException(SupportException.Code.NOT_FOUND_OR_FORBIDDEN);
    }
}
