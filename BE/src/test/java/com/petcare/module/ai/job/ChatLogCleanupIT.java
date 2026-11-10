package com.petcare.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.ai.service.ChatLogCleanupService;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * ST23 — xóa {@code ai_chat_logs} quá hạn lưu (BR-AI-24, docs/adr/0130): chỉ dòng có {@code created_at} cũ hơn
 * {@code now − ai.log.retention_days} (so sánh chặt, đúng biên thì giữ), hạn đọc từ [CFG] ở mỗi lượt, xóa theo lô, dừng
 * khi hết ngân sách thời gian, lô lỗi chỉ bị log. Chưa có nơi ghi nhật ký (chat là T77b) nên dòng được chèn tay.
 * Clock ở năm <b>1982</b>, cũ hơn mọi IT khác: job quét không cận dưới, mốc cắt chỉ chạm dòng của IT này; dòng của IT
 * được nhận ra bằng {@code llm_model = "ChatLogCleanupIT"}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ChatLogCleanupIT.TestBeans.class})
class ChatLogCleanupIT {

    private static final Instant T0 = Instant.parse("1982-06-15T04:00:29Z");
    private static final String TAG = "ChatLogCleanupIT";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private ChatLogCleanupService cleanup;
    @Autowired
    private ChatLogCleanupProperties properties;
    @Autowired
    private SystemConfigApi config;
    @Autowired
    private SystemConfigService configService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        jdbc.update("DELETE FROM ai_chat_logs WHERE llm_model = ?", TAG);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_reject_log_delete ON ai_chat_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_reject_log_delete()");
        jdbc.update("UPDATE system_configs SET value = '30' WHERE key = 'ai.log.retention_days'");
        configService.reload();
        jdbc.update("DELETE FROM ai_chat_logs WHERE llm_model = ?", TAG);
    }

    private ChatLogCleanupJob job() {
        return new ChatLogCleanupJob(cleanup, properties, config, clock);
    }

    private long log(Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO ai_chat_logs (result, latency_ms, llm_model, created_at)
                VALUES ('ANSWERED', 120, ?, ?) RETURNING id""", Long.class, TAG, Timestamp.from(createdAt));
    }

    private List<Long> remaining() {
        return jdbc.queryForList("SELECT id FROM ai_chat_logs WHERE llm_model = ? ORDER BY id", Long.class, TAG);
    }

    @Test
    void deletesOnlyLogsOlderThanTheRetentionAndKeepsTheExactBoundary() {
        Instant cutoff = T0.minus(Duration.ofDays(30));
        long old = log(T0.minus(Duration.ofDays(31)));
        long justOlder = log(cutoff.minusSeconds(1));
        long exactly = log(cutoff);
        long recent = log(T0.minus(Duration.ofDays(29)));
        long fresh = log(T0);

        job().run();

        assertThat(remaining()).containsExactly(exactly, recent, fresh).doesNotContain(old, justOlder);
    }

    @Test
    void readsTheRetentionFromTheConfigAtRunTime() {
        long fifteenDays = log(T0.minus(Duration.ofDays(15)));
        long fiveDays = log(T0.minus(Duration.ofDays(5)));
        job().run();
        assertThat(remaining()).containsExactly(fifteenDays, fiveDays);

        jdbc.update("UPDATE system_configs SET value = '10' WHERE key = 'ai.log.retention_days'");
        configService.reload();
        job().run();

        assertThat(remaining()).containsExactly(fiveDays);
    }

    @Test
    void deletesInBatchesUntilNothingIsLeftToDelete() {
        for (int i = 1; i <= 5; i++) {
            log(T0.minus(Duration.ofDays(40 + i)));
        }
        long keep = log(T0.minus(Duration.ofDays(1)));
        ChatLogCleanupProperties small = new ChatLogCleanupProperties("-", 2, Duration.ofSeconds(60));

        new ChatLogCleanupJob(cleanup, small, config, clock).run();

        assertThat(remaining()).containsExactly(keep);
    }

    @Test
    void stopsAtTheTimeBudgetAndTheNextRunFinishesTheWork() {
        for (int i = 1; i <= 5; i++) {
            log(T0.minus(Duration.ofDays(40 + i)));
        }
        ChatLogCleanupProperties small = new ChatLogCleanupProperties("-", 2, Duration.ofSeconds(10));
        AtomicInteger calls = new AtomicInteger();
        // gọi 1 = mốc bắt đầu, gọi 2 = kiểm trước lô đầu (còn hạn), từ gọi 3 trở đi coi như đã quá ngân sách.
        LongSupplier ticker = () -> calls.incrementAndGet() <= 2 ? 0 : Duration.ofSeconds(11).toNanos();

        new ChatLogCleanupJob(cleanup, small, config, clock, ticker).run();

        assertThat(remaining()).as("chỉ một lô 2 dòng được xóa trước khi hết ngân sách").hasSize(3);

        new ChatLogCleanupJob(cleanup, small, config, clock).run();

        assertThat(remaining()).isEmpty();
    }

    @Test
    void aFailingBatchIsLoggedNotRethrownAndTheNextRunRecovers() {
        long old = log(T0.minus(Duration.ofDays(60)));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_reject_log_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: từ chối xóa nhật ký';
                END $$""");
        jdbc.execute("CREATE TRIGGER ai_it_reject_log_delete BEFORE DELETE ON ai_chat_logs FOR EACH ROW WHEN "
                + "(OLD.llm_model = '" + TAG + "') EXECUTE FUNCTION ai_it_reject_log_delete()");

        assertThatCode(() -> job().run()).doesNotThrowAnyException();
        assertThat(remaining()).containsExactly(old);

        jdbc.execute("DROP TRIGGER ai_it_reject_log_delete ON ai_chat_logs");
        job().run();

        assertThat(remaining()).isEmpty();
    }
}
