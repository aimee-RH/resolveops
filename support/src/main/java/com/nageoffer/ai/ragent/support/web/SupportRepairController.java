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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.support.repair.SupportRepairService;
import com.nageoffer.ai.ragent.support.repair.SupportRepairService.RepairView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.nageoffer.ai.ragent.support.web.SupportCaseController.actor;

@RestController
@RequestMapping("/support/runs/{runId}/repair")
@ConditionalOnProperty(name = "support.repair.enabled", havingValue = "true")
public class SupportRepairController {
    public record BoundRequest(String payloadHash) { }

    private final SupportRepairService repairs;

    public SupportRepairController(SupportRepairService repairs) { this.repairs = repairs; }

    @GetMapping
    public Result<RepairView> detail(@PathVariable String runId) {
        return Results.success(repairs.get(actor(), runId));
    }

    @PostMapping
    public Result<RepairView> propose(@PathVariable String runId) {
        return Results.success(repairs.propose(actor(), runId));
    }

    @PostMapping("/approve")
    public Result<RepairView> approve(@PathVariable String runId, @RequestBody BoundRequest request) {
        return Results.success(repairs.review(actor(), runId, request.payloadHash(), true));
    }

    @PostMapping("/reject")
    public Result<RepairView> reject(@PathVariable String runId, @RequestBody BoundRequest request) {
        return Results.success(repairs.review(actor(), runId, request.payloadHash(), false));
    }

    @PostMapping("/execute")
    public Result<RepairView> execute(@PathVariable String runId, @RequestBody BoundRequest request) {
        return Results.success(repairs.execute(actor(), runId, request.payloadHash()));
    }
}
