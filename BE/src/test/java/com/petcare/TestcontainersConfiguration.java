package com.petcare;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres thật cho test cần DB (docs/convention/backend/09-testing.md: {@code @SpringBootTest} + Testcontainers).
 * Cùng image với docker-compose.yml; {@code @ServiceConnection} cấp DataSource nên Flyway chạy migration thật
 * và Hibernate {@code ddl-auto=validate} kiểm tra entity ↔ schema. Dùng bằng {@code @Import(TestcontainersConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));
    }
}
