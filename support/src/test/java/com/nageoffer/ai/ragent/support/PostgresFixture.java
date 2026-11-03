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

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Properties;
import java.util.UUID;
import java.io.IOException;

abstract class PostgresFixture {
    protected JdbcTemplate sql;
    protected NamedParameterJdbcTemplate jdbc;
    private JdbcTemplate admin;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        String url = System.getenv("RESOLVEOPS_TEST_DB_URL");
        boolean external = url != null && !url.isBlank();
        if (!external) {
            try (var connection = LocalPostgres.INSTANCE.getPostgresDatabase().getConnection()) {
                url = connection.getMetaData().getURL();
            }
        }
        String user = external ? System.getenv().getOrDefault("RESOLVEOPS_TEST_DB_USER", "resolveops") : "postgres";
        String password = external ? System.getenv().getOrDefault("RESOLVEOPS_TEST_DB_PASSWORD", "resolveops-local-only") : "postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "resolveops_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource source = new DriverManagerDataSource(url, user, password);
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        new ResourceDatabasePopulator(new ClassPathResource("db/261102_support_domain.sql")).execute(source);
        sql = new JdbcTemplate(source);
        jdbc = new NamedParameterJdbcTemplate(source);
        seed();
    }

    private void seed() {
        sql.update("INSERT INTO support_tenant VALUES ('tenant-a', 'Acme'), ('tenant-b', 'Other customer')");
        sql.update("INSERT INTO support_access_grant VALUES ('operator', 'tenant-a', 'OPERATOR'), ('approver', 'tenant-a', 'APPROVER'), ('viewer', 'tenant-a', 'VIEWER'), ('other', 'tenant-b', 'OPERATOR')");
        sql.update("INSERT INTO support_subscription VALUES ('sub-a', 'tenant-a', 'PAID', 'PENDING', 7), ('sub-b', 'tenant-b', 'PAID', 'PENDING', 1)");
        sql.update("INSERT INTO support_case(id, tenant_id, scenario, resource_id, description) VALUES ('case-a', 'tenant-a', 'SUBSCRIPTION_ACTIVATION', 'sub-a', 'Paid but not activated'), ('case-b', 'tenant-b', 'SUBSCRIPTION_ACTIVATION', 'sub-b', 'Other customer case')");
        sql.update("INSERT INTO support_subscription_event(id, tenant_id, subscription_id, event_type, occurred_at) VALUES ('paid-a', 'tenant-a', 'sub-a', 'PAYMENT_CONFIRMED', CURRENT_TIMESTAMP - INTERVAL '2 hours'), ('failed-a', 'tenant-a', 'sub-a', 'ACTIVATION_FAILED', CURRENT_TIMESTAMP - INTERVAL '1 hour')");
    }

    @AfterEach
    void dropOwnSchema() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private static final class LocalPostgres {
        static final EmbeddedPostgres INSTANCE = start();
        private static EmbeddedPostgres start() {
            try {
                EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(0).start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { postgres.close(); } catch (IOException ignored) { }
                }));
                return postgres;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot start native PostgreSQL test process", e);
            }
        }
    }
}
