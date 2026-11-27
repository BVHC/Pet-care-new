package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
import com.petcare.module.care.job.PriorityNotificationJob;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.support.MutableClock;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;

/**
 * Kích gửi ngay email luồng HIGH sau commit (docs/adr/0154) trên Postgres 17 + GreenMail, với
 * {@code kick-enabled=true} (profile test tắt) và mọi cron ST20 tắt: thư tới mà không job nào chạy; rollback không gửi;
 * thư NORMAL không được kích; kích và job HIGH cùng lúc chỉ gửi một lần; SMTP sập thì dòng ở lại cho job.
 * Era tương lai 2112 (docs/architecture/testing-notes.md); dòng gắn email UUID, chỉ dọn dòng của mình.
 */
@SpringBootTest(properties = "app.jobs.notification-outbox.kick-enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PriorityEmailKickIT.TestBeans.class})
class PriorityEmailKickIT {

    private static final Instant T0 = Instant.parse("2112-01-05T02:00:00Z");
    private static final String NORMAL_TEMPLATE = "IT_NORMAL_NOTICE";
    private static final Duration WAIT = Duration.ofSeconds(10);

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
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String tag;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tag = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_email LIKE ?", "%@kick-%.petcare.test");
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'EMAIL', 'Thông báo {so}', 'Thông báo số {so}', 'Thông báo {so}', 'Thông báo số {so}',
                        '["so"]'::jsonb, '["so"]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, NORMAL_TEMPLATE);
    }

    /** OTP tới GreenMail ngay sau commit dù không job nào chạy; dòng SENT một lần, mã OTP bị xóa khỏi DB. */
    @Test
    void otpIsSentRightAfterCommitWithoutAnyJob() throws Exception {
        String email = email("an");

        enqueue(NotificationTemplateCode.OTP_REGISTER, email, Map.of("ma_otp", "123456", "thoi_han_phut", 5), false);

        assertThat(SMTP.waitForIncomingEmail(WAIT.toMillis(), 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];
        long id = rowId(email);
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly(email);
        assertThat(message.getMessageID()).isEqualTo("<notification-" + id + "@petcare.local>");
        awaitTrue(() -> "SENT".equals(row(id).get("status")));
        assertThat(row(id).get("attempts")).isEqualTo(1);
        assertThat(row(id).get("payload").toString()).doesNotContain("123456");
    }

    /** Transaction ghi outbox rollback: không có dòng, không có thư (listener chỉ chạy sau commit). */
    @Test
    void rollbackSendsNothing() throws Exception {
        String email = email("rollback");

        enqueue(NotificationTemplateCode.OTP_REGISTER, email, Map.of("ma_otp", "111111", "thoi_han_phut", 5), true);

        assertThat(SMTP.waitForIncomingEmail(1_500, 1)).isFalse();
        assertThat(count(email)).isZero();
    }

    /** Thư hàng loạt (NORMAL) không được kích: chỉ job NORMAL gửi. */
    @Test
    void normalLaneEmailIsNotKicked() throws Exception {
        String email = email("bulk");

        enqueue(NORMAL_TEMPLATE, email, Map.of("so", 7), false);

        assertThat(SMTP.waitForIncomingEmail(1_500, 1)).isFalse();
        assertThat(row(rowId(email)).get("status")).isEqualTo("PENDING");
        assertThat(row(rowId(email)).get("attempts")).isEqualTo(0);
    }

    /** Lần kích và job HIGH chạy cùng lúc trên một dòng: đúng một thư, một lần tính. */
    @Test
    void kickAndPriorityJobSendOnce() throws Exception {
        String email = email("race");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            enqueue(NotificationTemplateCode.OTP_REGISTER, email, Map.of("ma_otp", "222222", "thoi_han_phut", 5),
                    false);
            Future<?> job = executor.submit(priorityJob::run);
            job.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        long id = rowId(email);
        awaitTrue(() -> "SENT".equals(row(id).get("status")));

        assertThat(SMTP.waitForIncomingEmail(2_000, 2)).as("không có thư thứ hai").isFalse();
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
        assertThat(row(id).get("attempts")).isEqualTo(1);
    }

    /** SMTP sập lúc kích: dòng được hẹn thử lại theo backoff (1 phút), còn PENDING để job HIGH gửi sau. */
    @Test
    void kickWithSmtpDownLeavesRowForPriorityJob() throws Exception {
        SMTP.stop();
        String email = email("down");

        enqueue(NotificationTemplateCode.OTP_REGISTER, email, Map.of("ma_otp", "333333", "thoi_han_phut", 5), false);

        long id = rowId(email);
        awaitTrue(() -> ((Number) row(id).get("attempts")).intValue() == 1);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(row.get("last_error").toString()).contains("ConnectException");
    }

    // ---------------------------------------------------------------- helpers

    private String email(String name) {
        return name + "@kick-" + tag + ".petcare.test";
    }

    private void enqueue(String template, String email, Map<String, Object> payload, boolean rollback) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.enqueue(new NotificationRequest(template, Channel.EMAIL, null, email, payload, null));
            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    private long rowId(String email) {
        return jdbc.queryForObject("SELECT id FROM notification_outbox WHERE recipient_email = ?", Long.class, email);
    }

    private long count(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ?", Long.class,
                email);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM notification_outbox WHERE id = ?", id);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("PriorityEmailKickIT: hết thời gian chờ điều kiện");
            }
            Thread.sleep(50);
        }
    }
}
