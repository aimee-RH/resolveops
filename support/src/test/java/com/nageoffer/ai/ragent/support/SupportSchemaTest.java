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

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportSchemaTest extends PostgresFixture {
    @Test
    void schemaContainsBothTenantsAndRejectsCrossTenantEvent() {
        assertThat(sql.queryForObject("SELECT count(*) FROM support_tenant", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> sql.update("INSERT INTO support_subscription_event(id, tenant_id, subscription_id, event_type, occurred_at) VALUES ('invalid', 'tenant-b', 'sub-a', 'ACTIVATION_FAILED', CURRENT_TIMESTAMP)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidBusinessStatesAreRejectedByDatabase() {
        assertThatThrownBy(() -> sql.update("UPDATE support_subscription SET activation_state='UNKNOWN' WHERE id='sub-a'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
