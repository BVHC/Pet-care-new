package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.support.MutableClock;

/**
 * ST20 luồng IN_APP trên Postgres 17 (docs/adr/0014): dòng outbox IN_APP → đúng một dòng {@code notifications}
 * (exactly-once: chạy lại, chạy song song, lỗi sau INSERT), dòng không giao được → {@code FAILED} và không chặn dòng
 * sau, tồn đọng email không chặn IN_APP, câu chọn dùng index partial V6.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, InAppNotificationIT.TestBeans.class})
class InAppNotificationIT {

    private static final Instant T0 = Instant.parse("2026-10-07T02:00:00Z");
    /** Mẫu IN_APP chỉ có trong test: mẫu `_APP` thật seed cùng PR với module gửi đầu tiên (06 §8 Q4). */
    private static final String APP_TEMPLATE = "IT_INAPP_NOTICE_APP";
    private static final String EMAIL_TEMPLATE = "IT_INAPP_EMAIL_NOTICE";
    private static final String ACCOUNT_PREFIX = "inapp-it-";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationApi notifications;

    @Autowired
    private InAppNotificationJob job;

    @Autowired
    private InAppNotificationDeliveryService delivery;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM accounts WHERE email LIKE ?", ACCOUNT_PREFIX + "%");
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'IN_APP', 'Thông báo {so}', 'Nội dung số {so}', 'Thông báo {so}', 'Nội dung số {so}',
                        '["so"]'::jsonb, '["so"]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, APP_TEMPLATE);
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'EMAIL', 'Thư {so}', 'Thư số {so}', 'Thư {so}', 'Thư số {so}',
                        '["so"]'::jsonb, '["so"]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, EMAIL_TEMPLATE);
    }

    // ---------------------------------------------------------------- giao đúng một lần

    @Test
    void enqueuedRowBecomesExactlyOneNotification() {
        long account = account();
        enqueue(account, Map.of("so", 7, "ma_otp", "123456"), "/me/appointments/7");

        job.run();

        Map<String, Object> notification = jdbc.queryForMap("SELECT * FROM notifications");
        assertThat(notification.get("account_id")).isEqualTo(account);
        assertThat(notification.get("type")).isEqualTo("IT_INAPP_NOTICE");
        assertThat(notification.get("title")).isEqualTo("Thông báo 7");
        assertThat(notification.get("body")).isEqualTo("Nội dung số 7");
        assertThat(notification.get("link_url")).isEqualTo("/me/appointments/7");
        assertThat(notification.get("read_at")).isNull();

        Map<String, Object> row = onlyOutboxRow();
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("payload").toString()).doesNotContain("ma_otp").contains("\"so\"");

        clock.advance(Duration.ofSeconds(5));
        job.run();

        assertThat(notificationCount()).isEqualTo(1);
    }

    /** Use case rollback → không có dòng outbox → không có thông báo (transactional outbox, convention 07 §7.2). */
    @Test
    void rolledBackUseCaseProducesNoNotification() {
        long account = account();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.enqueue(request(account, Map.of("so", 1), null));
            status.setRollbackOnly();
        });

        job.run();

        assertThat(notificationCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class)).isZero();
    }

    @Test
    void concurrentDeliveryCreatesOneNotification() throws Exception {
        enqueue(account(), Map.of("so", 1), null);
        CountDownLatch start = new CountDownLatch(1);
        Callable<DispatchResult> call = () -> {
            await(start);
            return delivery.deliverNext(T0);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DispatchResult>> futures = List.of(executor.submit(call), executor.submit(call));
            start.countDown();
            List<DispatchResult> results = new ArrayList<>();
            for (Future<DispatchResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(DispatchResult.SENT, DispatchResult.NONE);
        } finally {
            executor.shutdownNow();
        }
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    void lockedRowIsSkippedThenDeliveredOnce() throws Exception {
        enqueue(account(), Map.of("so", 1), null);
        long id = jdbc.queryForObject("SELECT id FROM notification_outbox", Long.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        jdbc.queryForObject("SELECT id FROM notification_outbox WHERE id = ? FOR UPDATE", Long.class,
                                id);
                        locked.countDown();
                        await(release);
                    }));
            await(locked);
            long started = System.nanoTime();

            job.run();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(notificationCount()).isZero();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(onlyOutboxRow().get("status")).isEqualTo("PENDING");

        job.run();

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(onlyOutboxRow().get("status")).isEqualTo("SENT");
    }

    /**
     * INSERT {@code notifications} và {@code SENT} cùng transaction: lệnh UPDATE sang {@code SENT} lỗi lúc commit (trigger
     * tạm) → rollback cả thông báo đã INSERT, dòng giữ {@code PENDING}; lượt sau giao đúng một lần.
     */
    @Test
    void failureAfterInsertRollsBackNotificationToo() {
        enqueue(account(), Map.of("so", 1, "it_fail_on_sent", true), null);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_fail_on_sent_" + suffix;
        String trigger = "trg_it_fail_on_sent_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.status = 'SENT' AND (OLD.payload ->> 'it_fail_on_sent') IS NOT NULL THEN
                        RAISE EXCEPTION 'IT: lỗi sau INSERT notifications';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(function));
        jdbc.execute("CREATE TRIGGER %s BEFORE UPDATE ON notification_outbox FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, function));
        try {
            job.run();

            assertThat(notificationCount()).isZero();
            Map<String, Object> row = onlyOutboxRow();
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempts")).isEqualTo(0);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON notification_outbox");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        job.run();

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(onlyOutboxRow().get("status")).isEqualTo("SENT");
    }

    // ---------------------------------------------------------------- không giao được

    /** Dòng hỏng đứng đầu hàng thành FAILED và không chặn dòng sau trong cùng lượt (không có "dòng độc"). */
    @Test
    void undeliverableRowsFailWithoutBlockingLaterRows() {
        long account = account();
        long emailTemplateRow = insertInApp(EMAIL_TEMPLATE, account, "{\"so\": 1}", T0.minusSeconds(30));
        long missingVariableRow = insertInApp(APP_TEMPLATE, account, "{}", T0.minusSeconds(20));
        long longLinkRow = insertInApp(APP_TEMPLATE, account,
                "{\"so\": 1, \"link_url\": \"/" + "a".repeat(500) + "\"}", T0.minusSeconds(10));
        long goodRow = insertInApp(APP_TEMPLATE, account, "{\"so\": 2}", T0);

        job.run();

        assertThat(outboxRow(emailTemplateRow).get("status")).isEqualTo("FAILED");
        assertThat(outboxRow(emailTemplateRow).get("last_error").toString()).contains("EMAIL");
        assertThat(outboxRow(missingVariableRow).get("status")).isEqualTo("FAILED");
        assertThat(outboxRow(missingVariableRow).get("last_error").toString()).contains("[so]");
        assertThat(outboxRow(longLinkRow).get("status")).isEqualTo("FAILED");
        assertThat(outboxRow(goodRow).get("status")).isEqualTo("SENT");
        assertThat(jdbc.queryForList("SELECT title FROM notifications", String.class))
                .containsExactly("Thông báo 2");
    }

    @Test
    void longTitleIsDeliveredCutTo200Characters() {
        enqueue(account(), Map.of("so", "x".repeat(300)), null);

        job.run();

        String title = jdbc.queryForObject("SELECT title FROM notifications", String.class);
        assertThat(title).hasSize(200).endsWith("…");
    }

    // ---------------------------------------------------------------- tách khỏi email

    /** 200 thư đến hạn trước (SMTP sập): IN_APP vẫn giao, dòng email không bị đụng tới. */
    @Test
    void emailBacklogNeitherBlocksNorIsTouchedByInAppLane() {
        for (int i = 0; i < 200; i++) {
            insertEmail(T0.minusSeconds(600 - i));
        }
        enqueue(account(), Map.of("so", 1), null);

        job.run();

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE channel = 'EMAIL' AND status = 'PENDING' AND attempts = 0",
                Long.class)).isEqualTo(200);
    }

    /**
     * Câu chọn / đếm IN_APP thật của repository dùng index partial V6 kể cả khi có 2.000 dòng email PENDING — chặn việc
     * đổi điều kiện status / channel sang tham số bind (docs/adr/0014).
     */
    @Test
    void inAppQueriesUsePartialIndex() throws NoSuchMethodException {
        for (int i = 0; i < 2000; i++) {
            insertEmail(T0.minusSeconds(3600 - i));
        }
        long account = account();
        for (int i = 0; i < 3; i++) {
            insertInApp(APP_TEMPLATE, account, "{\"so\": 1}", T0);
        }
        jdbc.execute("ANALYZE notification_outbox");

        for (String method : List.of("lockNextDueInApp", "countDueInApp", "findOldestDueInApp")) {
            String sql = NotificationOutboxRepository.class.getMethod(method, Instant.class)
                    .getAnnotation(Query.class).value().replace(":now", "?");
            String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, at(T0)));
            assertThat(plan).as(method).contains("ix_notification_outbox_in_app_due");
        }
    }

    // ---------------------------------------------------------------- helpers

    private long account() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, ACCOUNT_PREFIX + UUID.randomUUID() + "@petcare.test");
    }

    private static NotificationRequest request(long account, Map<String, Object> payload, String linkUrl) {
        return new NotificationRequest(APP_TEMPLATE, Channel.IN_APP, account, null, payload, linkUrl);
    }

    private void enqueue(long account, Map<String, Object> payload, String linkUrl) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> notifications.enqueue(request(account, payload, linkUrl)));
    }

    private long insertInApp(String template, long account, String payloadJson, Instant due) {
        return jdbc.queryForObject("""
                INSERT INTO notification_outbox (channel, template_code, recipient_account_id, payload, status,
                                                 next_attempt_at)
                VALUES ('IN_APP', ?, ?, ?::jsonb, 'PENDING', ?) RETURNING id
                """, Long.class, template, account, payloadJson, at(due));
    }

    private void insertEmail(Instant due) {
        jdbc.update("""
                INSERT INTO notification_outbox (channel, template_code, recipient_email, payload, status, next_attempt_at)
                VALUES ('EMAIL', ?, 'an@petcare.test', '{"so": 1}'::jsonb, 'PENDING', ?)
                """, EMAIL_TEMPLATE, at(due));
    }

    private long notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class);
    }

    private Map<String, Object> onlyOutboxRow() {
        return jdbc.queryForMap("SELECT * FROM notification_outbox");
    }

    private Map<String, Object> outboxRow(long id) {
        return jdbc.queryForMap("SELECT * FROM notification_outbox WHERE id = ?", id);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("InAppNotificationIT: hết thời gian chờ latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
