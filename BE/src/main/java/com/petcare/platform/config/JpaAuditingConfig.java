package com.petcare.platform.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code created_at}/{@code updated_at} của entity lấy từ bean {@link Clock} (docs/adr/0015), không từ giờ JVM:
 * {@code AuditingEntityListener} của {@code TimestampedEntity}/{@code CreatedAtEntity} gọi {@link DateTimeProvider}
 * dưới đây. Test thay {@code Clock} thì cột thời điểm đi theo.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = JpaAuditingConfig.DATE_TIME_PROVIDER)
public class JpaAuditingConfig {

    static final String DATE_TIME_PROVIDER = "clockDateTimeProvider";

    @Bean(DATE_TIME_PROVIDER)
    public DateTimeProvider clockDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
