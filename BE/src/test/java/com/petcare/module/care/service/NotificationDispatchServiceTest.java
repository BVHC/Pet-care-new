package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.service.NotificationDispatchService.Claim;
import com.petcare.module.care.service.NotificationDispatchService.ClaimedMail;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.Settled;
import com.petcare.module.care.service.OutboxMailSender.Delivery;
import com.petcare.module.care.service.OutboxMailSender.Outcome;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Phần CSDL của ST20 email (docs/adr/0012, docs/adr/0154): bước nhận dòng (chọn theo luồng, dựng thư, lỗi dựng thư,
 * vé hạn giữ) và bước ghi kết quả (kiểm vé dưới khóa, phân loại lỗi, lùi lịch thử lại, trả dòng chưa thử, xóa khóa
 * nhạy cảm). Không có SMTP ở lớp này.
 */
class NotificationDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Instant TOKEN = NOW.plus(LEASE);
    private static final DispatchPolicy POLICY = new DispatchPolicy(5, Duration.ofMinutes(1), "no-reply@petcare.local");
    private static final TemplateView OTP = new TemplateView("OTP_REGISTER", "EMAIL", "Mã xác thực",
            "Xin chào,\nMã: {ma_otp} ({thoi_han_phut} phút)", List.of("ten_khach", "ma_otp", "thoi_han_phut"),
            List.of("ma_otp"));
    private static final TemplateView NOTICE = new TemplateView("IT_NOTICE", "EMAIL", "Thông báo {so}",
            "Nội dung {so}", List.of("so"), List.of("so"));

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final NotificationTemplateQueryApi templates = mock(NotificationTemplateQueryApi.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationDispatchService service = new NotificationDispatchService(outbox, templates,
            new NotificationTemplateRenderer(), mailSender, meters);

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(OTP));
        when(templates.findTemplate("IT_NOTICE")).thenReturn(Optional.of(NOTICE));
    }

    private static NotificationOutbox row(long id, String template, String email, Map<String, Object> payload) {
        NotificationOutbox row = NotificationOutbox.pending(Channel.EMAIL, template, email, null, payload,
                NOW.minusSeconds(4));
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private static NotificationOutbox otpRow() {
        return row(42, "OTP_REGISTER", "an@petcare.test", Map.of("ma_otp", "123456", "thoi_han_phut", 5));
    }

    private static NotificationOutbox noticeRow(long id, String email) {
        return row(id, "IT_NOTICE", email, Map.of("so", id));
    }

    private Claim claimHigh(NotificationOutbox row) {
        when(outbox.lockNextDueEmailIn(NOW, DeliveryLane.HIGH_TEMPLATES)).thenReturn(Optional.of(row));
        return service.claimNextHigh(NOW, POLICY, LEASE);
    }

    private Claim claimBatch(NotificationOutbox... rows) {
        when(outbox.lockNextDueEmailBatchNotIn(NOW, DeliveryLane.HIGH_TEMPLATES, 20)).thenReturn(List.of(rows));
        return service.claimNextNormalBatch(NOW, POLICY, 20, LEASE);
    }

    /** Dòng đang được ghi kết quả: {@code findByIdForUpdate} trả đúng dòng đã nhận (vé còn hiệu lực). */
    private void lockedForSettle(NotificationOutbox... rows) {
        for (NotificationOutbox row : rows) {
            when(outbox.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        }
    }

    private static ClaimedMail mail(NotificationOutbox row) {
        return new ClaimedMail(row.getId(), TOKEN, NOW.minusSeconds(4), row.getAttempts() == 0, null);
    }

    private Settled settle(DeliveryLane lane, Delivery... deliveries) {
        return service.settle(lane, LATER, POLICY, List.of(deliveries));
    }

    // ================================================================ bước nhận dòng

    @Test
    void noDueRowMeansNone() {
        when(outbox.lockNextDueEmailIn(any(), anyCollection())).thenReturn(Optional.empty());

        assertThat(service.claimNextHigh(NOW, POLICY, LEASE)).isEqualTo(Claim.NONE);
    }

    /** {@code SET LOCAL idle_in_transaction_session_timeout} chạy trước khi khóa dòng, ở cả ba cách nhận. */
    @Test
    void limitsIdleInTransactionBeforeLocking() {
        when(outbox.lockNextDueEmailIn(any(), anyCollection())).thenReturn(Optional.empty());
        when(outbox.lockNextDueEmailBatchNotIn(any(), anyCollection(), anyInt())).thenReturn(List.of());
        when(outbox.lockDueEmailById(anyLong(), any(), anyCollection())).thenReturn(Optional.empty());

        service.claimNextHigh(NOW, POLICY, LEASE);
        service.claimNextNormalBatch(NOW, POLICY, 20, LEASE);
        service.claimHighById(9L, NOW, POLICY, LEASE);

        InOrder order = inOrder(outbox);
        order.verify(outbox).limitIdleInTransaction("60s");
        order.verify(outbox).lockNextDueEmailIn(NOW, DeliveryLane.HIGH_TEMPLATES);
        order.verify(outbox).limitIdleInTransaction("60s");
        order.verify(outbox).lockNextDueEmailBatchNotIn(NOW, DeliveryLane.HIGH_TEMPLATES, 20);
        order.verify(outbox).limitIdleInTransaction("60s");
        order.verify(outbox).lockDueEmailById(9L, NOW, DeliveryLane.HIGH_TEMPLATES);
    }

    /**
     * Nhận dòng: thư được dựng đủ (tiêu đề, nội dung, Message-ID theo dòng, người gửi, người nhận), dòng bị đẩy
     * {@code next_attempt_at} tới hạn giữ (cắt micro giây) làm vé; trạng thái, số lần, payload chưa đổi.
     */
    @Test
    void claimComposesMessageAndPushesDueToLeaseWithoutChargingAttempt() throws Exception {
        NotificationOutbox row = otpRow();
        Instant nowWithNanos = NOW.plusNanos(1_234_567);
        when(outbox.lockNextDueEmailIn(nowWithNanos, DeliveryLane.HIGH_TEMPLATES)).thenReturn(Optional.of(row));

        Claim claim = service.claimNextHigh(nowWithNanos, POLICY, LEASE);

        assertThat(claim.locked()).isEqualTo(1);
        ClaimedMail mail = claim.mails().get(0);
        Instant token = NOW.plus(LEASE).plusNanos(1_234_000);
        assertThat(mail.outboxId()).isEqualTo(42L);
        assertThat(mail.claimToken()).isEqualTo(token);
        assertThat(mail.dueAt()).isEqualTo(NOW.minusSeconds(4));
        assertThat(mail.firstAttempt()).isTrue();
        MimeMessage message = mail.message();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực");
        assertThat((String) message.getContent()).isEqualTo("Xin chào,\nMã: 123456 (5 phút)");
        assertThat(message.getMessageID()).isEqualTo("<notification-42@petcare.local>");
        assertThat(message.getFrom()).extracting(Address::toString).containsExactly("no-reply@petcare.local");
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("an@petcare.test");

        assertThat(row.getNextAttemptAt()).isEqualTo(token);
        assertThat(row.isClaimedBy(token)).isTrue();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getPayload()).containsKey("ma_otp");
        verify(mailSender, never()).send(any(MimeMessage[].class));
    }

    /**
     * ADMIN được để xuống dòng trong tiêu đề mẫu (UC10, docs/adr/0037 mục 6): Jakarta Mail gấp dòng thành dòng tiếp nối
     * nên không chèn được header. Test này khóa hành vi đó nếu đổi thư viện mail.
     */
    @Test
    void lineBreakInSubjectCannotInjectHeaders() throws Exception {
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(new TemplateView("OTP_REGISTER", "EMAIL",
                "Mã xác thực\r\nBcc: attacker@evil.test\nX-Injected: 1", OTP.body(), OTP.allowedVars(),
                OTP.requiredVars())));

        MimeMessage message = claimHigh(otpRow()).mails().get(0).message();
        message.saveChanges();

        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getHeader("X-Injected")).isNull();
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("an@petcare.test");
        assertThat(message.getHeader("Subject")).hasSize(1);
    }

    /** Lô NORMAL: mỗi dòng một thư theo đúng thứ tự, cùng một vé; dòng dựng lỗi vĩnh viễn FAILED ngay, không gửi. */
    @Test
    void batchClaimsComposableRowsInOrderAndFailsBrokenOnes() throws Exception {
        NotificationOutbox first = noticeRow(1, "a@petcare.test");
        NotificationOutbox noEmail = noticeRow(2, null);
        NotificationOutbox third = noticeRow(3, "c@petcare.test");

        Claim claim = claimBatch(first, noEmail, third);

        assertThat(claim.locked()).isEqualTo(3);
        assertThat(claim.failed()).isEqualTo(1);
        assertThat(claim.retried()).isZero();
        assertThat(claim.mails()).extracting(ClaimedMail::outboxId).containsExactly(1L, 3L);
        assertThat(claim.mails()).extracting(ClaimedMail::claimToken).containsOnly(TOKEN);
        for (ClaimedMail mail : claim.mails()) {
            mail.message().saveChanges();
        }
        assertThat(claim.mails()).extracting(m -> m.message().getMessageID())
                .containsExactly("<notification-1@petcare.local>", "<notification-3@petcare.local>");
        assertThat(noEmail.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(List.of(first, third)).extracting(NotificationOutbox::getNextAttemptAt).containsOnly(TOKEN);
    }

    /** Kích sau commit chỉ nhận đúng dòng HIGH của mình; dòng đã được job nhận (không còn đến hạn) thì NONE. */
    @Test
    void claimByIdUsesHighTemplatesAndReturnsNoneWhenTaken() {
        when(outbox.lockDueEmailById(42L, NOW, DeliveryLane.HIGH_TEMPLATES)).thenReturn(Optional.empty());

        assertThat(service.claimHighById(42L, NOW, POLICY, LEASE)).isEqualTo(Claim.NONE);

        NotificationOutbox row = otpRow();
        when(outbox.lockDueEmailById(42L, NOW, DeliveryLane.HIGH_TEMPLATES)).thenReturn(Optional.of(row));
        assertThat(service.claimHighById(42L, NOW, POLICY, LEASE).mails()).extracting(ClaimedMail::outboxId)
                .containsExactly(42L);
        assertThat(row.getNextAttemptAt()).isEqualTo(TOKEN);
    }

    // ---------------------------------------------------------------- lỗi vĩnh viễn khi dựng thư: FAILED ngay

    @Test
    void missingRecipientEmailFailsWithoutClaiming() {
        assertPermanentFailure(row(42, "OTP_REGISTER", null, Map.of("ma_otp", "1")), "recipient_email");
    }

    @Test
    void unknownTemplateFailsWithoutClaiming() {
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.empty());
        assertPermanentFailure(otpRow(), "Không có mẫu");
    }

    @Test
    void templateOfOtherChannelFailsWithoutClaiming() {
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(
                new TemplateView("OTP_REGISTER", "IN_APP", "s", "b", List.of(), List.of())));
        assertPermanentFailure(otpRow(), "IN_APP");
    }

    @Test
    void missingRequiredVariableFailsWithoutClaiming() {
        assertPermanentFailure(row(42, "OTP_REGISTER", "an@petcare.test", Map.of("thoi_han_phut", 5)), "ma_otp");
    }

    @Test
    void malformedAddressFailsWithoutClaiming() {
        assertPermanentFailure(row(42, "OTP_REGISTER", "not an email", Map.of("ma_otp", "1")), "Không dựng được thư");
    }

    private void assertPermanentFailure(NotificationOutbox row, String errorPart) {
        Claim claim = claimHigh(row);

        assertThat(claim.locked()).isEqualTo(1);
        assertThat(claim.failed()).isEqualTo(1);
        assertThat(claim.mails()).isEmpty();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).contains(errorPart);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    /** Lỗi DB khi dựng thư thoát ra để rollback cả bước nhận: không vé nào được ghi. */
    @Test
    void databaseErrorWhileComposingPropagates() {
        doThrow(new DataAccessResourceFailureException("db down")).when(templates).findTemplate("IT_NOTICE");
        NotificationOutbox row = noticeRow(1, "a@petcare.test");

        assertThatThrownBy(() -> claimBatch(row)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(row.getAttempts()).isZero();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    // ================================================================ bước ghi kết quả

    /** Luồng IN_APP thuộc InAppNotificationDeliveryService (docs/adr/0014): gọi nhầm là lỗi lập trình. */
    @Test
    void inAppLaneIsRejected() {
        assertThatThrownBy(() -> settle(DeliveryLane.IN_APP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.backlog(DeliveryLane.IN_APP, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sentMarksSentScrubsPayloadAndRecordsQueueLatencyOnFirstAttempt() {
        NotificationOutbox row = otpRow();
        row.claim(TOKEN);
        lockedForSettle(row);

        Settled settled = settle(DeliveryLane.HIGH, new Delivery(mail(row), Outcome.SENT, null));

        assertThat(settled).isEqualTo(new Settled(1, 0, 0, 0));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isEqualTo(LATER);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp").containsEntry("thoi_han_phut", 5);
        assertThat(meters.get("notification.outbox.queue.latency").tag("lane", "HIGH").timer()
                .totalTime(TimeUnit.SECONDS)).isEqualTo(6.0);
    }

    /**
     * Vé lệch: hạn giữ đã hết và lượt khác nhận lại dòng (vé mới), hoặc dòng đã vào trạng thái cuối. Bước ghi của lượt
     * cũ không đổi gì, kể cả khi thư của nó đã tới máy chủ.
     */
    @Test
    void staleTicketChangesNothing() {
        NotificationOutbox reclaimed = noticeRow(1, "a@petcare.test");
        reclaimed.claim(TOKEN.plusSeconds(600));
        NotificationOutbox alreadySent = noticeRow(2, "b@petcare.test");
        alreadySent.markSent(NOW);
        when(outbox.findByIdForUpdate(3L)).thenReturn(Optional.empty());
        lockedForSettle(reclaimed, alreadySent);

        Settled settled = settle(DeliveryLane.NORMAL,
                new Delivery(mail(reclaimed), Outcome.SENT, null),
                new Delivery(new ClaimedMail(2L, TOKEN, NOW, true, null), Outcome.FAILED, new MessagingException("x")),
                new Delivery(new ClaimedMail(3L, TOKEN, NOW, true, null), Outcome.SENT, null));

        assertThat(settled).isEqualTo(new Settled(0, 0, 0, 3));
        assertThat(reclaimed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reclaimed.getAttempts()).isZero();
        assertThat(reclaimed.getNextAttemptAt()).isEqualTo(TOKEN.plusSeconds(600));
        assertThat(alreadySent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(alreadySent.getAttempts()).isEqualTo((short) 1);
    }

    /** Khóa theo id tăng dần dù kết quả gửi đến theo thứ tự khác: hai lượt ghi không chờ chéo nhau. */
    @Test
    void settleLocksRowsInIdOrder() {
        NotificationOutbox five = noticeRow(5, "e@petcare.test");
        NotificationOutbox two = noticeRow(2, "b@petcare.test");
        five.claim(TOKEN);
        two.claim(TOKEN);
        lockedForSettle(five, two);

        settle(DeliveryLane.NORMAL, new Delivery(mail(five), Outcome.SENT, null),
                new Delivery(mail(two), Outcome.SENT, null));

        InOrder order = inOrder(outbox);
        order.verify(outbox).findByIdForUpdate(2L);
        order.verify(outbox).findByIdForUpdate(5L);
    }

    /** Thư chưa thử (lô dừng vì lỗi máy chủ): dòng về hạn cũ, không tính lần gửi. */
    @Test
    void notAttemptedReleasesRowToOriginalDue() {
        NotificationOutbox row = noticeRow(1, "a@petcare.test");
        row.claim(TOKEN);
        lockedForSettle(row);

        assertThat(settle(DeliveryLane.NORMAL, new Delivery(mail(row), Outcome.NOT_ATTEMPTED, null)))
                .isEqualTo(new Settled(0, 0, 0, 0));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.minusSeconds(4));
    }

    @Test
    void recipientRejectedByServerFails() throws Exception {
        NotificationOutbox row = otpRow();
        row.claim(TOKEN);
        lockedForSettle(row);
        SendFailedException rejected = new SendFailedException("550 user unknown", null, null, null,
                new Address[] {new InternetAddress("an@petcare.test")});

        assertThat(settle(DeliveryLane.HIGH, new Delivery(mail(row), Outcome.FAILED, rejected)))
                .isEqualTo(new Settled(0, 0, 1, 0));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("550 user unknown");
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    // ---------------------------------------------------------------- lỗi tạm thời: lùi 1, 2, 4, 8 phút, lần 5 FAILED

    @ParameterizedTest(name = "attempts={0} → lùi {1} phút")
    @CsvSource({"0, 1", "1, 2", "2, 4", "3, 8"})
    void transientErrorBacksOffExponentiallyFromSettleTime(short attemptsBefore, long minutes) {
        NotificationOutbox row = otpRow();
        ReflectionTestUtils.setField(row, "attempts", attemptsBefore);
        row.claim(TOKEN);
        lockedForSettle(row);

        assertThat(settle(DeliveryLane.HIGH, new Delivery(mail(row), Outcome.FAILED,
                new MessagingException("451 try again later")))).isEqualTo(new Settled(0, 1, 0, 0));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo((short) (attemptsBefore + 1));
        assertThat(row.getNextAttemptAt()).isEqualTo(LATER.plus(Duration.ofMinutes(minutes)));
        assertThat(row.getLastError()).contains("451 try again later");
        assertThat(row.getPayload()).containsKey("ma_otp");
    }

    @Test
    void fifthTransientFailureMarksFailedAndScrubs() {
        NotificationOutbox row = otpRow();
        ReflectionTestUtils.setField(row, "attempts", (short) 4);
        row.claim(TOKEN);
        lockedForSettle(row);

        assertThat(settle(DeliveryLane.HIGH, new Delivery(mail(row), Outcome.FAILED,
                new MailSendException("451 try again later")))).isEqualTo(new Settled(0, 0, 1, 0));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 5);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    /** Lỗi kết nối được ghi kèm gốc lỗi để chẩn đoán; dòng lùi lịch như lỗi tạm. */
    @Test
    void connectionFailureIsRetriedWithRootCauseInLastError() {
        NotificationOutbox row = otpRow();
        row.claim(TOKEN);
        lockedForSettle(row);

        settle(DeliveryLane.HIGH, new Delivery(mail(row), Outcome.FAILED,
                new MessagingException("connect", new ConnectException("Connection refused"))));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isEqualTo(LATER.plus(Duration.ofMinutes(1)));
        assertThat(row.getLastError()).contains("ConnectException");
    }

    @Test
    void databaseErrorWhileSettlingPropagates() {
        doThrow(new DataAccessResourceFailureException("db down")).when(outbox).findByIdForUpdate(1L);
        NotificationOutbox row = noticeRow(1, "a@petcare.test");

        assertThatThrownBy(() -> settle(DeliveryLane.NORMAL, new Delivery(mail(row), Outcome.SENT, null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void masksEmailForLogs() {
        assertThat(NotificationDispatchService.mask("an@petcare.test")).isEqualTo("a***@petcare.test");
        assertThat(NotificationDispatchService.mask("a@petcare.test")).isEqualTo("***@petcare.test");
    }
}
