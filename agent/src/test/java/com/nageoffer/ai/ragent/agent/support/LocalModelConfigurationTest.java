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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalModelConfigurationTest {
    static Binder configuration() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(source("base", "application.yaml"));
        environment.getPropertySources().addFirst(source("local-model", "application-local-model.yaml"));
        return Binder.get(environment);
    }

    private static PropertiesPropertySource source(String name, String file) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../bootstrap/src/main/resources/" + file));
        return new PropertiesPropertySource(name, yaml.getObject());
    }

    @Test
    void profileRoutesAgentAndEveryChatTierOnlyToLocalModel() {
        var binder = configuration();
        var agent = binder.bind("agent", AgentProperties.class).get();
        var ai = binder.bind("ai", AIModelProperties.class).get();
        assertThat(agent.getChat().getProvider()).isEqualTo("ollama");
        assertThat(agent.getChat().getModel()).isEqualTo("resolveops-qwen3:4b");
        assertThat(agent.getChat().getTemperature()).isZero();
        assertThat(agent.getChat().getReasoningEffort()).isEqualTo("low");
        assertThat(agent.getMaxConcurrentRunsPerUser()).isEqualTo(1);
        assertThat(ai.getProviders().get("ollama").getUrl()).isEqualTo("http://127.0.0.1:11434");
        assertThat(ai.getChat().getCandidates()).hasSize(1);
        assertThat(ai.getChat().getCandidates().get(0).getProvider()).isEqualTo("ollama");
        ai.getChat().getTiers().values().forEach(tier ->
                assertThat(tier.getCandidates()).containsExactly("resolveops-local"));
    }
}
