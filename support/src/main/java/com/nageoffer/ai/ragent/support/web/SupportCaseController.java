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

package com.nageoffer.ai.ragent.support.web;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.support.SupportCase;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/support")
public class SupportCaseController {
    private final SupportAccessService access;
    private final SupportRunStore runs;

    public SupportCaseController(SupportAccessService access, SupportRunStore runs) {
        this.access = access;
        this.runs = runs;
    }

    public static String actor() {
        String id = UserContext.getUserId();
        SupportAccessService.requireActor(id);
        return id;
    }

    @GetMapping("/cases")
    public Result<List<SupportCase>> cases() { return Results.success(access.listCases(actor())); }

    @GetMapping("/cases/{caseId}")
    public Result<SupportCase> detail(@PathVariable String caseId) {
        return Results.success(access.requireCase(actor(), caseId, SupportAccessService.Permission.READ));
    }

    @GetMapping("/cases/{caseId}/runs")
    public Result<List<SupportRunStore.RunView>> history(@PathVariable String caseId) {
        return Results.success(runs.forCase(actor(), caseId));
    }

    @GetMapping("/runs/{runId}")
    public Result<SupportRunStore.RunView> run(@PathVariable String runId) { return Results.success(runs.get(actor(), runId)); }

    @GetMapping("/runs/{runId}/events")
    public Result<List<Map<String, Object>>> events(@PathVariable String runId, @RequestParam(defaultValue = "0") long after) {
        return Results.success(runs.events(actor(), runId, after));
    }

    @GetMapping("/runs/{runId}/evidence")
    public Result<List<SupportRunStore.Evidence>> evidence(@PathVariable String runId) {
        return Results.success(runs.evidence(actor(), runId));
    }

    @GetMapping("/runs/{runId}/evidence/{evidenceId}")
    public Result<SupportRunStore.Evidence> evidence(@PathVariable String runId, @PathVariable String evidenceId) {
        return Results.success(runs.evidence(actor(), runId, evidenceId));
    }
}
