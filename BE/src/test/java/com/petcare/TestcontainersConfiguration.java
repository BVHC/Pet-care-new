package com.petcare;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres thật cho test cần DB (docs/convention/backend/09-testing.md: {@code @SpringBootTest} + Testcontainers).
 * Cùng image với docker-compose.yml ({@code pgvector/pgvector:pg17}: Postgres 17 + extension {@code vector} cho phân hệ
 * AI, docs/adr/0110); {@code @ServiceConnection} cấp DataSource nên Flyway chạy migration thật
 * và Hibernate {@code ddl-auto=validate} kiểm tra entity ↔ schema. Dùng bằng {@code @Import(TestcontainersConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Ảnh không chính thức nên phải khai báo là bản thay thế tương thích của {@code postgres}. */
    static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
