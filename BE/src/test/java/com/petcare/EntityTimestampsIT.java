package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.SystemConfig;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.SystemConfigRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.support.MutableClock;

import jakarta.persistence.EntityManager;

/**
 * {@code created_at}/{@code updated_at} lấy từ bean {@code Clock} (docs/adr/0015, trả nợ D004): đặt clock về năm 2003
 * — khác hẳn giờ JVM — rồi kiểm cột do JPA ghi. Phủ entity id IDENTITY (tạo + sửa), entity id gán tay (sửa) và bảng
 * LOG {@code audit_logs} (chỉ tạo).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, EntityTimestampsIT.TestBeans.class})
class EntityTimestampsIT {

    private static final Instant T0 = Instant.parse("2003-05-01T01:02:03Z");
    private static final String EMAIL_PREFIX = "entity-timestamps-it-";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private SystemConfigRepository configs;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM accounts WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    @Test
    void identityEntityGetsBothTimestampsFromClockAndUpdateMovesOnlyUpdatedAt() {
        String email = EMAIL_PREFIX + UUID.randomUUID() + "@example.com";
        Long id = tx.execute(status -> accounts.save(
                Account.registerCustomer(email, "hash", Instant.parse("2030-01-01T00:00:00Z"))).getId());

        assertThat(column("accounts", "created_at", id)).isEqualTo(T0);
        assertThat(column("accounts", "updated_at", id)).isEqualTo(T0);

        clock.advance(Duration.ofHours(1));
        tx.executeWithoutResult(status -> accounts.findById(id).orElseThrow().verify());

        assertThat(column("accounts", "created_at", id)).isEqualTo(T0);
        assertThat(column("accounts", "updated_at", id)).isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    @Test
    void assignedIdEntityUpdateTakesUpdatedAtFromClock() {
        Instant later = T0.plus(Duration.ofDays(2));
        clock.set(later);

        Instant[] seen = new Instant[2];
        tx.executeWithoutResult(status -> {
            SystemConfig config = configs.findAll().get(0);
            ReflectionTestUtils.setField(config, "description", config.getDescription() + " (EntityTimestampsIT)");
            entityManager.flush();
            seen[0] = jdbc.queryForObject("SELECT updated_at FROM system_configs WHERE key = ?", Timestamp.class,
                    config.getConfigKey()).toInstant();
            seen[1] = jdbc.queryForObject("SELECT created_at FROM system_configs WHERE key = ?", Timestamp.class,
                    config.getConfigKey()).toInstant();
            status.setRollbackOnly(); // không để lại thay đổi cho IT khác
        });

        assertThat(seen[0]).isEqualTo(later);
        assertThat(seen[1]).isNotEqualTo(later);
    }

    @Test
    void logTableCreatedAtComesFromClock() {
        String reason = "EntityTimestampsIT-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> auditRecorder.record(AuditEntry.of("ENTITY_TIMESTAMP_CHECKED")
                .entity("accounts", null).reason(reason)));

        Instant createdAt = jdbc.queryForObject("SELECT created_at FROM audit_logs WHERE reason = ?",
                Timestamp.class, reason).toInstant();
        assertThat(createdAt).isEqualTo(T0);
    }

    private Instant column(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Timestamp.class, id)
                .toInstant();
    }
}
