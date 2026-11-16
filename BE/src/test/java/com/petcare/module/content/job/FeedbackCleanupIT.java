package com.petcare.module.content.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.content.repository.FeedbackRepository;
import com.petcare.module.content.service.FeedbackCleanupService;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * ST24 — xóa cứng {@code feedbacks} quá hạn lưu (BR-DG-05, docs/adr/0122, 0131): chỉ dòng có {@code created_at} cũ hơn
 * {@code now − feedback.retention_days} (so sánh chặt, đúng biên thì giữ), hạn đọc từ [CFG] ở mỗi lượt, xóa theo lô, dừng
 * khi hết ngân sách thời gian, lô lỗi chỉ bị log, dòng đang bị khóa bị bỏ qua (không chờ), truy vấn dùng
 * {@code ix_feedbacks_created_at}. Xóa là không hoàn tác nên IT cũng kiểm cái <b>không</b> bị xóa: feedback của khách
 * khác, và các bảng khác có dòng cũ hơn mốc cắt ({@code customers}, {@code notifications}, {@code ai_chat_logs}).
 * Clock ở năm <b>1981</b>, cũ hơn mọi IT khác: job quét không cận dưới, mốc cắt (giữa 1981) không chạm dòng của IT
 * nào khác; dòng của IT này nhận ra bằng khách có tên {@code FeedbackCleanupIT …} và {@code llm_model = "FeedbackCleanupIT"}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FeedbackCleanupIT.TestBeans.class})
class FeedbackCleanupIT {

    private static final Instant T0 = Instant.parse("1981-06-15T03:30:31Z");
    private static final Instant CUTOFF = T0.minus(Duration.ofDays(30));
    private static final String TAG = "FeedbackCleanupIT";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private FeedbackCleanupService cleanup;
    @Autowired
    private FeedbackCleanupProperties properties;
    @Autowired
    private SystemConfigApi config;
    @Autowired
    private SystemConfigService configService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long customer;
    private final List<Long> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        customer = newCustomer(T0.minus(Duration.ofDays(400)));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS fb_it_reject_delete ON feedbacks");
        jdbc.execute("DROP FUNCTION IF EXISTS fb_it_reject_delete()");
        jdbc.update("UPDATE system_configs SET value = '30' WHERE key = 'feedback.retention_days'");
        configService.reload();
        jdbc.update("DELETE FROM feedbacks WHERE customer_id IN (SELECT id FROM customers WHERE full_name LIKE ?)",
                TAG + "%");
        jdbc.update("DELETE FROM ai_chat_logs WHERE llm_model = ?", TAG);
        for (long account : accounts) {
            jdbc.update("DELETE FROM notifications WHERE account_id = ?", account);
        }
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", TAG + "%");
        for (long account : accounts) {
            jdbc.update("DELETE FROM accounts WHERE id = ?", account);
        }
        accounts.clear();
    }

    // ---------------------------------------------------------------- dữ liệu

    private FeedbackCleanupJob job() {
        return new FeedbackCleanupJob(cleanup, properties, config, clock);
    }

    /** Job với lô và ngân sách riêng, không nghỉ giữa các lô. */
    private FeedbackCleanupJob job(int batchSize, Duration timeBudget, LongSupplier ticker) {
        return new FeedbackCleanupJob(cleanup, new FeedbackCleanupProperties("-", batchSize, Duration.ZERO, timeBudget),
                config, clock, ticker, duration -> { });
    }

    private long newCustomer(Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel, created_at, updated_at)
                VALUES (?, '0900000001', 'COUNTER', ?, ?) RETURNING id
                """, Long.class, TAG + " " + UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private long feedback(Instant createdAt) {
        return feedback(customer, createdAt);
    }

    private long feedback(long owner, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO feedbacks (customer_id, topic, rating, content, created_at, updated_at)
                VALUES (?, 'OTHER', 4, 'Nội dung phản hồi của IT dọn feedback', ?, ?) RETURNING id
                """, Long.class, owner, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private List<Long> remaining() {
        return jdbc.queryForList("SELECT id FROM feedbacks WHERE customer_id = ? ORDER BY id", Long.class, customer);
    }

    private Set<Long> feedbackIdsOfOthers() {
        Set<Long> ids = new HashSet<>(jdbc.queryForList("SELECT id FROM feedbacks", Long.class));
        ids.removeAll(jdbc.queryForList(
                "SELECT id FROM feedbacks WHERE customer_id IN (SELECT id FROM customers WHERE full_name LIKE ?)", Long.class,
                TAG + "%"));
        return ids;
    }

    // ---------------------------------------------------------------- chọn đúng dòng

    @Test
    void deletesOnlyFeedbackOlderThanTheRetentionAndKeepsTheExactBoundary() {
        // [CFG] cạnh khóa lưu giữ: nhầm sang feedback.max_per_day (= 5) sẽ xóa cả dòng 6 / 20 / 29 ngày dưới đây.
        assertThat(config.getInt(ConfigKey.FEEDBACK_MAX_PER_DAY)).isEqualTo(5);
        assertThat(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).isEqualTo(30);
        long ancient = feedback(T0.minus(Duration.ofDays(400)));
        long old = feedback(T0.minus(Duration.ofDays(31)));
        long justOlder = feedback(CUTOFF.minusSeconds(1));
        long exactly = feedback(CUTOFF);
        long twentyNine = feedback(T0.minus(Duration.ofDays(29)));
        long twenty = feedback(T0.minus(Duration.ofDays(20)));
        long six = feedback(T0.minus(Duration.ofDays(6)));
        long yesterday = feedback(T0.minus(Duration.ofDays(1)));
        long fresh = feedback(T0);

        job().run();

        assertThat(remaining()).containsExactly(exactly, twentyNine, twenty, six, yesterday, fresh)
                .doesNotContain(ancient, old, justOlder);
    }

    /** Xóa là không hoàn tác: ngoài dòng quá hạn của IT, không dòng nào khác trong DB được mất. */
    @Test
    void doesNotTouchFeedbackOfOtherCustomersOrRowsOfOtherTables() {
        long otherCustomer = newCustomer(T0.minus(Duration.ofDays(500)));
        long othersFresh = feedback(otherCustomer, T0.minus(Duration.ofDays(2)));
        long expired = feedback(T0.minus(Duration.ofDays(60)));
        Set<Long> othersBefore = feedbackIdsOfOthers();
        // Dòng cũ hơn mốc cắt ở bảng khác: không được đụng tới (tài khoản + thông báo + nhật ký hỏi đáp AI).
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "feedback-cleanup-it-" + UUID.randomUUID() + "@petcare.test");
        accounts.add(account);
        Timestamp old = Timestamp.from(T0.minus(Duration.ofDays(400)));
        long notification = jdbc.queryForObject("""
                INSERT INTO notifications (account_id, type, title, body, created_at, updated_at)
                VALUES (?, 'IT_CLEANUP', 'Tiêu đề', 'Nội dung', ?, ?) RETURNING id
                """, Long.class, account, old, old);
        long chatLog = jdbc.queryForObject("""
                INSERT INTO ai_chat_logs (result, latency_ms, llm_model, created_at)
                VALUES ('ANSWERED', 120, ?, ?) RETURNING id
                """, Long.class, TAG, old);
        long customersBefore = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);

        job().run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedbacks WHERE id = ?", Long.class, expired)).isZero();
        assertThat(feedbackIdsOfOthers()).as("feedback không thuộc IT này").isEqualTo(othersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedbacks WHERE id = ?", Long.class, othersFresh)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).as("customers")
                .isEqualTo(customersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Long.class, notification))
                .as("notifications").isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chat_logs WHERE id = ?", Long.class, chatLog))
                .as("ai_chat_logs").isOne();
    }

    // ---------------------------------------------------------------- [CFG]

    @Test
    void readsTheRetentionFromTheConfigAtRunTime() {
        long fifteenDays = feedback(T0.minus(Duration.ofDays(15)));
        long fiveDays = feedback(T0.minus(Duration.ofDays(5)));
        job().run();
        assertThat(remaining()).containsExactly(fifteenDays, fiveDays);

        jdbc.update("UPDATE system_configs SET value = '10' WHERE key = 'feedback.retention_days'");
        configService.reload();
        job().run();

        assertThat(remaining()).containsExactly(fiveDays);
    }

    /** Hạn ngoài khoảng 7–365 bị {@code reload()} từ chối và cache giữ giá trị tốt cuối cùng: job không bao giờ chạy với giá trị xấu. */
    @Test
    void anOutOfRangeRetentionIsRejectedAndTheJobKeepsUsingTheLastGoodValue() {
        long old = feedback(T0.minus(Duration.ofDays(40)));
        long twenty = feedback(T0.minus(Duration.ofDays(20)));
        long fresh = feedback(T0);
        jdbc.update("UPDATE system_configs SET value = '0' WHERE key = 'feedback.retention_days'");

        assertThatThrownBy(configService::reload).isInstanceOf(IllegalStateException.class);
        job().run();

        assertThat(remaining()).as("hạn 0 không được xóa mọi thứ").containsExactly(twenty, fresh).doesNotContain(old);
    }

    // ---------------------------------------------------------------- lô, ngân sách

    @Test
    void deletesInBatchesUntilNothingIsLeftToDelete() {
        for (int i = 1; i <= 5; i++) {
            feedback(T0.minus(Duration.ofDays(40 + i)));
        }
        long keep = feedback(T0.minus(Duration.ofDays(1)));

        job(2, Duration.ofSeconds(60), System::nanoTime).run();

        assertThat(remaining()).containsExactly(keep);
    }

    @Test
    void stopsAtTheTimeBudgetAndTheNextRunFinishesTheWork() {
        List<Long> expired = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            expired.add(feedback(T0.minus(Duration.ofDays(40 + i))));
        }
        AtomicInteger calls = new AtomicInteger();
        // gọi 1 = mốc bắt đầu, gọi 2 = kiểm trước lô đầu (còn hạn), từ gọi 3 trở đi coi như đã quá ngân sách.
        LongSupplier ticker = () -> calls.incrementAndGet() <= 2 ? 0 : Duration.ofSeconds(11).toNanos();

        job(2, Duration.ofSeconds(10), ticker).run();

        assertThat(remaining()).as("chỉ một lô 2 dòng được xóa trước khi hết ngân sách").hasSize(3)
                .isSubsetOf(expired);

        job(2, Duration.ofSeconds(60), System::nanoTime).run();

        assertThat(remaining()).isEmpty();
    }

    // ---------------------------------------------------------------- lỗi, khóa

    @Test
    void aFailingBatchIsLoggedNotRethrownDeletesNothingAndTheNextRunRecovers() {
        long old = feedback(T0.minus(Duration.ofDays(60)));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fb_it_reject_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: từ chối xóa feedback';
                END $$""");
        jdbc.execute("CREATE TRIGGER fb_it_reject_delete BEFORE DELETE ON feedbacks FOR EACH ROW WHEN "
                + "(OLD.customer_id = " + customer + ") EXECUTE FUNCTION fb_it_reject_delete()");

        assertThatCode(() -> job().run()).doesNotThrowAnyException();
        assertThat(remaining()).as("lô lỗi rollback trọn, không mất dòng nào").containsExactly(old);

        jdbc.execute("DROP TRIGGER fb_it_reject_delete ON feedbacks");
        job().run();

        assertThat(remaining()).isEmpty();
    }

    /** {@code SKIP LOCKED}: dòng đang bị tx khác khóa bị bỏ qua (job không chờ), được xóa ở lượt sau. */
    @Test
    void aRowLockedByAnotherTransactionIsSkippedNotWaitedForAndDeletedNextRun() throws SQLException {
        long locked = feedback(T0.minus(Duration.ofDays(60)));
        long free = feedback(T0.minus(Duration.ofDays(61)));
        long keep = feedback(T0.minus(Duration.ofDays(1)));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM feedbacks WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, locked);
                lock.executeQuery().close();

                assertTimeoutPreemptively(Duration.ofSeconds(20), () -> job().run());

                assertThat(remaining()).containsExactly(locked, keep).doesNotContain(free);
            } finally {
                other.rollback();
            }
        }

        job().run();

        assertThat(remaining()).containsExactly(keep);
    }

    // ---------------------------------------------------------------- index (ADR-0131)

    /**
     * Mỗi lô chọn dòng quá hạn bằng {@code ix_feedbacks_created_at} (V51), không quét tuần tự toàn bảng. Lấy đúng câu
     * SELECT con từ {@code @Query} của repository nên đổi câu SQL thì test này đổi theo. 50k dòng trải 5,7 năm, mốc cắt
     * chỉ khớp 2%; chạy trong transaction rồi rollback. Như {@code AuditLogViewIT.queriesUseExpectedIndex}, kết quả
     * phụ thuộc thống kê bảng (D021).
     */
    @Test
    void theBatchSelectUsesTheCreatedAtIndex() throws NoSuchMethodException {
        String sql = FeedbackRepository.class.getMethod("deleteExpiredBefore", Instant.class, int.class)
                .getAnnotation(Query.class).value();
        String select = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'));
        String explain = "EXPLAIN " + select.replace(":cutoff", "timestamptz '1960-02-12 00:00:00+00'")
                .replace(":limit", "500");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String plan = tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO feedbacks (customer_id, topic, content, created_at, updated_at)
                    SELECT ?, 'OTHER', 'Nội dung phản hồi cho phép EXPLAIN', ts, ts
                    FROM (SELECT timestamptz '1960-01-01 00:00:00+00' + g * interval '1 hour' AS ts
                          FROM generate_series(1, 50000) g) s
                    """, customer);
            jdbc.execute("ANALYZE feedbacks");
            String result = String.join("\n", jdbc.queryForList(explain, String.class));
            status.setRollbackOnly();
            return result;
        });

        assertThat(plan).contains("ix_feedbacks_created_at").doesNotContain("Seq Scan on feedbacks");
    }

}
