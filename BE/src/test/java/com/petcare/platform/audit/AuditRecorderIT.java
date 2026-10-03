package com.petcare.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * {@link AuditRecorder} trên Postgres 17 thật (docs/adr/0001-audit-recording.md). {@code audit_logs} không xóa được
 * (trigger BR-QT-16) nên mỗi test đánh dấu bản ghi của mình bằng một {@code reason} ngẫu nhiên.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuditRecorderIT {

    record LockSnapshot(String status, boolean isLocked, Instant lockedUntil) {
    }

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ServletWebServerApplicationContext webContext;

    private TransactionTemplate tx;
    private String marker;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        marker = UUID.randomUUID().toString();
    }

    @Test
    void recordCommitsWithBusinessTransaction() {
        tx.executeWithoutResult(status -> recorder.record(AuditEntry.of("ACCOUNT_LOCKED")
                .entity("accounts", 7L)
                .before(new LockSnapshot("ACTIVE", false, null))
                .after(new LockSnapshot("ACTIVE", true, Instant.parse("2026-10-03T08:00:00Z")))
                .reason(marker)
                .actor(1L, "admin@petcare.vn")));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_account_id, actor_email, action, entity_type, entity_id,
                       before_data->>'status' AS before_status, before_data->>'isLocked' AS before_locked,
                       after_data->>'isLocked' AS after_locked, after_data->>'lockedUntil' AS after_locked_until,
                       jsonb_typeof(before_data) AS before_type, ip_address, created_at
                FROM audit_logs WHERE reason = ?""", marker);

        assertThat(row).containsEntry("actor_account_id", 1L)
                .containsEntry("actor_email", "admin@petcare.vn")
                .containsEntry("action", "ACCOUNT_LOCKED")
                .containsEntry("entity_type", "accounts")
                .containsEntry("entity_id", 7L)
                .containsEntry("before_status", "ACTIVE")
                .containsEntry("before_locked", "false")
                .containsEntry("after_locked", "true")
                .containsEntry("after_locked_until", "2026-10-03T08:00:00Z")
                .containsEntry("before_type", "object");
        assertThat(row.get("ip_address")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void recordRollsBackWithBusinessTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            recorder.record(AuditEntry.of("ORDER_PAID").entity("orders", 1L).reason(marker));
            throw new IllegalStateException("business step failed after audit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countMarked()).isZero();
    }

    @Test
    void recordRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> recorder.record(AuditEntry.of("ORDER_PAID").reason(marker)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(countMarked()).isZero();
    }

    @Test
    void recordIndependentlySurvivesOuterRollback() {
        tx.executeWithoutResult(status -> {
            recorder.recordIndependently(AuditEntry.of("ACCESS_DENIED").reason(marker).actor(null, "x@example.com"));
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            recorder.recordIndependently(AuditEntry.of("ACCESS_DENIED").reason(marker).actor(null, "y@example.com"));
            throw new IllegalStateException("refused");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countMarked()).isEqualTo(2);
    }

    @Test
    void recordIndependentlyWorksWithoutOuterTransaction() {
        recorder.recordIndependently(AuditEntry.of("LOGIN_FAILED").reason(marker).actor(null, "typed@example.com"));

        assertThat(countMarked()).isEqualTo(1);
    }

    @Test
    void actorAccountIdHasNoForeignKey() {
        tx.executeWithoutResult(status -> recorder.record(
                AuditEntry.of("LOGIN_FAILED").reason(marker).actor(999_999L, "deleted-pending@example.com")));

        assertThat(countMarked()).isEqualTo(1);
    }

    @Test
    void tomcatTrustsForwardedHeadersOnlyFromInternalProxies() {
        TomcatWebServer webServer = (TomcatWebServer) webContext.getWebServer();
        Valve[] valves = webServer.getTomcat().getEngine().getPipeline().getValves();

        RemoteIpValve remoteIp = Arrays.stream(valves)
                .filter(RemoteIpValve.class::isInstance)
                .map(RemoteIpValve.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("RemoteIpValve missing: forward-headers-strategy is not native"));

        Pattern internalProxies = Pattern.compile(remoteIp.getInternalProxies());
        assertThat(internalProxies.matcher("172.18.0.5").matches()).as("Docker network").isTrue();
        assertThat(internalProxies.matcher("203.0.113.9").matches()).as("public address").isFalse();
        assertThat(remoteIp.getRemoteIpHeader()).isEqualToIgnoringCase("X-Forwarded-For");
    }

    private int countMarked() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE reason = ?", Integer.class, marker);
    }
}
