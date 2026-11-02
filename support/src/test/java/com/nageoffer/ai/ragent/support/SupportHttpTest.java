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

package com.nageoffer.ai.ragent.support;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.support.access.SupportAccessService;
import com.nageoffer.ai.ragent.support.run.SupportRunStore;
import com.nageoffer.ai.ragent.support.web.SupportCaseController;
import com.nageoffer.ai.ragent.support.web.SupportExceptionAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportHttpTest extends PostgresFixture {
    private MockMvc mvc() {
        var access = new SupportAccessService(jdbc);
        return MockMvcBuilders.standaloneSetup(new SupportCaseController(access, new SupportRunStore(jdbc, access)))
                .setControllerAdvice(new SupportExceptionAdvice()).build();
    }

    private void login(String id) { UserContext.set(LoginUser.builder().userId(id).build()); }

    @AfterEach
    void clearIdentity() { UserContext.clear(); }

    @Test
    void missingIdentityCannotBeReplacedByClientHeaders() throws Exception {
        mvc().perform(get("/support/cases").header("X-User-Id", "operator").header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedContextGetsOnlyGrantedCases() throws Exception {
        login("operator");
        mvc().perform(get("/support/cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value("case-a"));
        mvc().perform(get("/support/cases/case-b")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("NOT_FOUND_OR_FORBIDDEN"));
        mvc().perform(get("/support/cases/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("NOT_FOUND_OR_FORBIDDEN"));
    }

    @Test
    void otherTenantCannotReadRunOrEvidenceById() throws Exception {
        var store = new SupportRunStore(jdbc, new SupportAccessService(jdbc));
        var scope = store.create("operator", "case-a", "http-test", Map.of("mode", "test"));
        store.start(scope);
        var evidence = store.record(scope, "SUBSCRIPTION", "sub-a", "7", Map.of("state", "PENDING"));
        login("other");
        mvc().perform(get("/support/runs/" + scope.runId())).andExpect(status().isNotFound());
        mvc().perform(get("/support/runs/" + scope.runId() + "/evidence/" + evidence.id())).andExpect(status().isNotFound());
    }
}
