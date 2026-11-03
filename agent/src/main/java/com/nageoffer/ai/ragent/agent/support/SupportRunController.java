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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.support.SupportException;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import com.nageoffer.ai.ragent.support.web.SupportCaseController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/support")
@ConditionalOnProperty(name = "support.investigation.enabled", havingValue = "true")
public class SupportRunController {
    public record Request(String requestKey, String question) { }
    private final SupportRunService runs;
    public SupportRunController(SupportRunService runs) { this.runs = runs; }

    @PostMapping("/cases/{caseId}/runs")
    public Result<SupportRunStore.RunView> submit(@PathVariable String caseId, @RequestBody Request request) {
        String actor = SupportCaseController.actor();
        if (request == null) throw new SupportException(SupportException.Code.INVALID_ARGUMENT);
        return Results.success(runs.submit(actor, caseId, request.requestKey(), request.question()));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result<SupportRunStore.RunView> cancel(@PathVariable String runId) {
        return Results.success(runs.cancel(SupportCaseController.actor(), runId));
    }
}
