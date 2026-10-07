package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.care.job.NotificationOutboxJob;
import com.petcare.module.care.job.NotificationOutboxProperties;
import com.petcare.module.care.job.PriorityNotificationJob;
import com.petcare.module.care.service.DeliveryLane;
import com.petcare.module.care.service.NotificationDispatchService;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.support.MutableClock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Worker ST20 trên Postgres 17 + SMTP thật trong JVM (GreenMail, cổng 3025 của profile test) — docs/adr/0012: gửi
 * và xóa mã OTP, luồng HIGH không xếp sau tồn đọng NORMAL, thử lại khi SMTP sập, SMTP treo không giữ transaction,
 * không gửi trùng, bỏ qua dòng đang khóa, không đụng dòng IN_APP, migration V4.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NotificationOutboxIT.TestBeans.class})
class NotificationOutboxIT {

    private static final Instant T0 = Instant.parse("2026-10-07T02:00:00Z");
    /** Mẫu NORMAL chỉ có trong test: hiện migration mới seed OTP_REGISTER (thuộc HIGH). */
    private static final String NORMAL_TEMPLATE = "IT_NORMAL_NOTICE";
    private static final String APP_TEMPLATE = "IT_APP_NOTICE";

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@example.com", "test-app-password"))
            .withPerMethodLifecycle(true);

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
    private PriorityNotificationJob priorityJob;

    @Autowired
    private NotificationOutboxJob normalJob;

    @Autowired
    private NotificationDispatchService dispatch;

    @Autowired
    private NotificationOutboxProperties properties;

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'EMAIL', 'Thông báo {so}', 'Thông báo số {so}', 'Thông báo {so}', 'Thông báo số {so}',
                        '["so"]'::jsonb, '["so"]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, NORMAL_TEMPLATE);
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'IN_APP', 'App', 'App', 'App', 'App', '[]'::jsonb, '[]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, APP_TEMPLATE);
    }

    // ---------------------------------------------------------------- gửi được

    @Test
    void otpEmailIsSentWithFixedMessageIdAndCodeIsRemovedFromDb() throws Exception {
        enqueueOtp("an@petcare.test", "123456");
        long id = onlyRowId();

        priorityJob.run();

        MimeMessage message = singleMessage();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực đăng ký tài khoản Pet Care");
        assertThat((String) message.getContent()).startsWith("Xin chào,").contains("123456").contains("5 phút");
        assertThat(message.getMessageID()).isEqualTo("<notification-" + id + "@petcare.local>");
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("an@petcare.test");

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(((java.sql.Timestamp) row.get("sent_at")).toInstant()).isEqualTo(T0);
        assertThat(row.get("payload").toString()).doesNotContain("ma_otp").doesNotContain("123456")
                .contains("thoi_han_phut");
    }

    /** Lượt thứ hai không gửi lại dòng đã SENT. */
    @Test
    void secondRunDoesNotResend() {
        enqueueOtp("an@petcare.test", "123456");

        priorityJob.run();
        priorityJob.run();

        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    // ---------------------------------------------------------------- hai luồng

    /**
     * 200 thư NORMAL ghi trước (đến hạn sớm hơn) và 1 OTP: một lượt HIGH gửi OTP ngay, không đụng thư NORMAL.
     * Nếu chỉ có một hàng đợi FIFO, OTP phải chờ sau 200 thư.
     */
    @Test
    void priorityLaneSendsOtpAheadOfNormalBacklog() throws Exception {
        jdbc.update("""
                INSERT INTO notification_outbox (channel, template_code, recipient_email, payload, status, next_attempt_at)
                SELECT 'EMAIL', ?, 'bulk' || g || '@petcare.test', jsonb_build_object('so', g), 'PENDING', ?
                FROM generate_series(1, 200) g
                """, NORMAL_TEMPLATE, at(T0.minus(Duration.ofHours(1))));
        enqueueOtp("otp@petcare.test", "654321");

        priorityJob.run();

        assertThat(singleMessage().getAllRecipients()).extracting(Address::toString)
                .containsExactly("otp@petcare.test");
        assertThat(count("status = 'PENDING' AND template_code = '" + NORMAL_TEMPLATE + "'")).isEqualTo(200);
    }

    @Test
    void normalLaneLeavesHighTemplatesToPriorityLane() throws Exception {
        enqueueOtp("otp@petcare.test", "654321");
        long normal = insertEmail(NORMAL_TEMPLATE, "bulk@petcare.test", "{\"so\": 7}", T0);

        normalJob.run();

        assertThat(singleMessage().getSubject()).isEqualTo("Thông báo 7");
        assertThat(row(normal).get("status")).isEqualTo("SENT");
        assertThat(count("status = 'PENDING' AND template_code = 'OTP_REGISTER'")).isEqualTo(1);
    }

    @Test
    void inAppRowsAreNeverPicked() {
        long inApp = jdbc.queryForObject("""
                INSERT INTO notification_outbox (channel, template_code, payload, status, next_attempt_at)
                VALUES ('IN_APP', ?, '{}'::jsonb, 'PENDING', ?) RETURNING id
                """, Long.class, APP_TEMPLATE, at(T0));

        priorityJob.run();
        normalJob.run();

        Map<String, Object> row = row(inApp);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    // ---------------------------------------------------------------- luồng NORMAL theo lô (docs/adr/0017)

    /** 20 thư NORMAL (đúng một lô mặc định) đi trên một kết nối SMTP, mỗi thư giữ {@code Message-ID} theo dòng. */
    @Test
    void normalLaneSendsBatchOverOneConnection() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ids.add(insertEmail(NORMAL_TEMPLATE, "bulk" + i + "@petcare.test", "{\"so\": " + i + "}", T0));
        }
        double connectionsBefore = normalConnections();

        normalJob.run();

        assertThat(SMTP.waitForIncomingEmail(10_000, 20)).isTrue();
        assertThat(SMTP.getReceivedMessages()).hasSize(20);
        assertThat(normalConnections() - connectionsBefore).as("một kết nối cho cả lô").isEqualTo(1.0);
        assertThat(count("status = 'SENT' AND attempts = 1")).isEqualTo(20);
        assertThat(Arrays.stream(SMTP.getReceivedMessages()).map(NotificationOutboxIT::messageId).toList())
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(id -> "<notification-" + id + "@petcare.local>")
                        .toList());
    }

    /**
     * SMTP câm khi lô NORMAL kết nối: timeout đọc lời chào (3 s ở profile test) cắt lô, chỉ dòng đầu bị tính một lần
     * thử, các dòng khác nguyên vẹn; không còn phiên "idle in transaction".
     */
    @Test
    void hungSmtpStopsNormalBatchAndLeavesRestUntouched() throws Exception {
        long first = insertEmail(NORMAL_TEMPLATE, "a@petcare.test", "{\"so\": 1}", T0);
        long second = insertEmail(NORMAL_TEMPLATE, "b@petcare.test", "{\"so\": 2}", T0);
        long third = insertEmail(NORMAL_TEMPLATE, "c@petcare.test", "{\"so\": 3}", T0);
        int originalPort = mailSender.getPort();
        try (ServerSocket silent = new ServerSocket(0)) {
            mailSender.setPort(silent.getLocalPort());
            long started = System.nanoTime();

            normalJob.run();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            mailSender.setPort(originalPort);
        }

        assertThat(row(first).get("attempts")).isEqualTo(1);
        assertThat(row(first).get("status")).isEqualTo("PENDING");
        assertThat(((java.sql.Timestamp) row(first).get("next_attempt_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofMinutes(1)));
        for (long untouched : List.of(second, third)) {
            assertThat(row(untouched).get("attempts")).isEqualTo(0);
            assertThat(row(untouched).get("status")).isEqualTo("PENDING");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND state LIKE 'idle in transaction%'
                """, Long.class)).isZero();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    // ---------------------------------------------------------------- lỗi

    @Test
    void missingRequiredVariableFailsWithoutSending() {
        long id = insertEmail(NotificationTemplateCode.OTP_REGISTER, "an@petcare.test", "{}", T0);

        priorityJob.run();

        assertThat(row(id).get("status")).isEqualTo("FAILED");
        assertThat(row(id).get("last_error").toString()).contains("ma_otp");
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    /** SMTP sập: lùi 1 phút; sau 5 lần → FAILED và mã OTP bị xóa khỏi DB. */
    @Test
    void smtpDownRetriesWithBackoffThenFails() {
        SMTP.stop();
        enqueueOtp("an@petcare.test", "123456");
        long id = onlyRowId();

        priorityJob.run();

        Map<String, Object> first = row(id);
        assertThat(first.get("status")).isEqualTo("PENDING");
        assertThat(first.get("attempts")).isEqualTo(1);
        assertThat(((java.sql.Timestamp) first.get("next_attempt_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(first.get("last_error").toString()).contains("ConnectException");

        for (int attempt = 2; attempt <= 5; attempt++) {
            clock.advance(Duration.ofMinutes(10));
            priorityJob.run();
        }

        Map<String, Object> last = row(id);
        assertThat(last.get("status")).isEqualTo("FAILED");
        assertThat(last.get("attempts")).isEqualTo(5);
        assertThat(last.get("payload").toString()).doesNotContain("123456");
    }

    /**
     * SMTP nhận kết nối nhưng không trả lời: timeout đọc (3 s ở profile test) cắt lần gửi, dòng được lên lịch thử lại,
     * không còn phiên "idle in transaction" nào giữ khóa.
     */
    @Test
    void hungSmtpIsCutByTimeoutAndReleasesTransaction() throws Exception {
        enqueueOtp("an@petcare.test", "123456");
        long id = onlyRowId();
        int originalPort = mailSender.getPort();
        try (ServerSocket silent = new ServerSocket(0)) {
            mailSender.setPort(silent.getLocalPort());
            long started = System.nanoTime();

            priorityJob.run();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            mailSender.setPort(originalPort);
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND state LIKE 'idle in transaction%'
                """, Long.class)).isZero();
    }

    // ---------------------------------------------------------------- đồng thời

    /** Dòng đang bị transaction khác khóa: worker bỏ qua ({@code SKIP LOCKED}), không chờ, không gửi. */
    @Test
    void lockedRowIsSkipped() throws Exception {
        enqueueOtp("an@petcare.test", "123456");
        long id = onlyRowId();
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

            priorityJob.run();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(SMTP.getReceivedMessages()).isEmpty();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(row(id).get("status")).isEqualTo("PENDING");
        assertThat(row(id).get("attempts")).isEqualTo(0);
    }

    /** Hai luồng gọi cùng lúc trên một dòng: đúng một lần gửi. */
    @Test
    void concurrentDispatchSendsOnce() throws Exception {
        enqueueOtp("an@petcare.test", "123456");
        DispatchPolicy policy = new DispatchPolicy(properties.maxAttempts(), properties.initialBackoff(),
                properties.mailFrom());
        CountDownLatch start = new CountDownLatch(1);
        Callable<DispatchResult> call = () -> {
            await(start);
            return dispatch.dispatchNext(DeliveryLane.HIGH, T0, policy);
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
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    // ---------------------------------------------------------------- migration V4

    @Test
    void v4ChangedOtpGreetingAndAddedLaneIndex() {
        Map<String, Object> template = jdbc.queryForMap(
                "SELECT body, default_body FROM notification_templates WHERE code = 'OTP_REGISTER'");
        assertThat(template.get("body").toString()).startsWith("Xin chào,").doesNotContain("{ten_khach}");
        assertThat(template.get("default_body")).isEqualTo(template.get("body"));
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'notification_outbox'", String.class))
                .anySatisfy(def -> assertThat(def).contains("(status, template_code, next_attempt_at)"));
    }

    // ---------------------------------------------------------------- helpers

    private void enqueueOtp(String email, String code) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> notifications.enqueue(
                new NotificationRequest(NotificationTemplateCode.OTP_REGISTER, Channel.EMAIL, null, email,
                        Map.of("ma_otp", code, "thoi_han_phut", 5), null)));
    }

    private long insertEmail(String template, String email, String payloadJson, Instant due) {
        return jdbc.queryForObject("""
                INSERT INTO notification_outbox (channel, template_code, recipient_email, payload, status, next_attempt_at)
                VALUES ('EMAIL', ?, ?, ?::jsonb, 'PENDING', ?) RETURNING id
                """, Long.class, template, email, payloadJson, at(due));
    }

    private long onlyRowId() {
        return jdbc.queryForObject("SELECT id FROM notification_outbox", Long.class);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM notification_outbox WHERE id = ?", id);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE " + where, Long.class);
    }

    private static MimeMessage singleMessage() {
        assertThat(SMTP.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] messages = SMTP.getReceivedMessages();
        assertThat(messages).hasSize(1);
        return messages[0];
    }

    private double normalConnections() {
        Counter counter = meters.find("notification.outbox.smtp.connections").tag("lane", "NORMAL").counter();
        return counter == null ? 0 : counter.count();
    }

    private static String messageId(MimeMessage message) {
        try {
            return message.getMessageID();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("NotificationOutboxIT: hết thời gian chờ latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
