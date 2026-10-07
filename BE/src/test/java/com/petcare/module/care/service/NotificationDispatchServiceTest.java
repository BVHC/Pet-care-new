package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.service.NotificationDispatchService.BatchOutcome;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.care.service.SmtpBatchSender.Attempt;
import com.petcare.module.care.service.SmtpBatchSender.BatchResult;
import com.petcare.module.care.service.SmtpBatchSender.Outcome;
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
 * Một lần gửi của ST20 (docs/adr/0012): chọn dòng theo luồng, dựng thư, phân loại lỗi vĩnh viễn / tạm thời / mức kết
 * nối, lùi lịch thử lại, xóa khóa nhạy cảm. Luồng NORMAL theo lô (docs/adr/0017): ma trận lỗi của lô.
 */
class NotificationDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final DispatchPolicy POLICY = new DispatchPolicy(5, Duration.ofMinutes(1), "no-reply@petcare.local");
    private static final TemplateView OTP = new TemplateView("OTP_REGISTER", "EMAIL", "Mã xác thực",
            "Xin chào,\nMã: {ma_otp} ({thoi_han_phut} phút)", List.of("ten_khach", "ma_otp", "thoi_han_phut"),
            List.of("ma_otp"));

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final NotificationTemplateQueryApi templates = mock(NotificationTemplateQueryApi.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpBatchSender batchSender = mock(SmtpBatchSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationDispatchService service = new NotificationDispatchService(outbox, templates,
            new NotificationTemplateRenderer(), mailSender, batchSender, meters);

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(OTP));
    }

    private static NotificationOutbox row(String email, Map<String, Object> payload) {
        NotificationOutbox row = NotificationOutbox.pending(Channel.EMAIL, "OTP_REGISTER", email, null, payload,
                NOW.minusSeconds(4));
        ReflectionTestUtils.setField(row, "id", 42L);
        return row;
    }

    private static NotificationOutbox otpRow() {
        return row("an@petcare.test", Map.of("ma_otp", "123456", "thoi_han_phut", 5));
    }

    private NotificationOutbox due(NotificationOutbox row) {
        when(outbox.lockNextDueEmailIn(NOW, DeliveryLane.HIGH_TEMPLATES)).thenReturn(Optional.of(row));
        return row;
    }

    private DispatchResult dispatch() {
        return service.dispatchNext(DeliveryLane.HIGH, NOW, POLICY);
    }

    private MimeMessage sentMessage() throws MessagingException {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        return message;
    }

    // ---------------------------------------------------------------- chọn dòng

    /** Luồng IN_APP thuộc InAppNotificationDeliveryService (docs/adr/0014): gọi nhầm không được coi như NORMAL. */
    @Test
    void inAppLaneIsRejected() {
        assertThatThrownBy(() -> service.dispatchNext(DeliveryLane.IN_APP, NOW, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.backlog(DeliveryLane.IN_APP, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outbox, never()).lockNextDueEmailNotIn(any(), anyCollection());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void noDueRowMeansNone() {
        when(outbox.lockNextDueEmailIn(any(), anyCollection())).thenReturn(Optional.empty());

        assertThat(dispatch()).isEqualTo(DispatchResult.NONE);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    /** {@code SET LOCAL idle_in_transaction_session_timeout} chạy trước khi khóa dòng. */
    @Test
    void limitsIdleInTransactionBeforeLockingRow() {
        when(outbox.lockNextDueEmailIn(any(), anyCollection())).thenReturn(Optional.empty());

        dispatch();

        InOrder order = inOrder(outbox);
        order.verify(outbox).limitIdleInTransaction("60s");
        order.verify(outbox).lockNextDueEmailIn(NOW, DeliveryLane.HIGH_TEMPLATES);
    }

    @Test
    void normalLaneSelectsComplementOfHighTemplates() {
        when(outbox.lockNextDueEmailNotIn(any(), anyCollection())).thenReturn(Optional.empty());

        assertThat(service.dispatchNext(DeliveryLane.NORMAL, NOW, POLICY)).isEqualTo(DispatchResult.NONE);

        verify(outbox).lockNextDueEmailNotIn(NOW, DeliveryLane.HIGH_TEMPLATES);
        verify(outbox, never()).lockNextDueEmailIn(any(), anyCollection());
    }

    // ---------------------------------------------------------------- gửi được

    @Test
    void sendsRenderedMessageWithFixedMessageIdAndScrubsPayload() throws Exception {
        NotificationOutbox row = due(otpRow());

        assertThat(dispatch()).isEqualTo(DispatchResult.SENT);

        MimeMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực");
        assertThat((String) message.getContent()).isEqualTo("Xin chào,\nMã: 123456 (5 phút)");
        assertThat(message.getMessageID()).isEqualTo("<notification-42@petcare.local>");
        assertThat(message.getFrom()).extracting(Address::toString).containsExactly("no-reply@petcare.local");
        assertThat(message.getAllRecipients()).extracting(Address::toString).containsExactly("an@petcare.test");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp").containsEntry("thoi_han_phut", 5);
    }

    @Test
    void firstAttemptRecordsQueueLatency() {
        due(otpRow());

        dispatch();

        assertThat(meters.get("notification.outbox.queue.latency").tag("lane", "HIGH").timer().totalTime(
                java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(4.0);
    }

    // ---------------------------------------------------------------- lỗi vĩnh viễn: FAILED ngay, không gửi

    @Test
    void missingRecipientEmailFailsWithoutSending() {
        NotificationOutbox row = due(row(null, Map.of("ma_otp", "1")));

        assertPermanentFailure(row, "recipient_email");
    }

    @Test
    void unknownTemplateFailsWithoutSending() {
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.empty());
        NotificationOutbox row = due(otpRow());

        assertPermanentFailure(row, "Không có mẫu");
    }

    @Test
    void templateOfOtherChannelFailsWithoutSending() {
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(
                new TemplateView("OTP_REGISTER", "IN_APP", "s", "b", List.of(), List.of())));
        NotificationOutbox row = due(otpRow());

        assertPermanentFailure(row, "IN_APP");
    }

    @Test
    void missingRequiredVariableFailsWithoutSending() {
        NotificationOutbox row = due(row("an@petcare.test", Map.of("thoi_han_phut", 5)));

        assertPermanentFailure(row, "ma_otp");
    }

    @Test
    void malformedAddressFailsWithoutSending() {
        NotificationOutbox row = due(row("not an email", Map.of("ma_otp", "1")));

        assertPermanentFailure(row, "Không dựng được thư");
    }

    @Test
    void recipientRejectedByServerFails() throws Exception {
        NotificationOutbox row = due(otpRow());
        SendFailedException rejected = new SendFailedException("550 user unknown", null, null, null,
                new Address[] {new InternetAddress("an@petcare.test")});
        Map<Object, Exception> failed = new HashMap<>();
        failed.put(new Object(), rejected);
        doThrow(new MailSendException(failed)).when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.FAILED);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    private void assertPermanentFailure(NotificationOutbox row, String errorPart) {
        assertThat(dispatch()).isEqualTo(DispatchResult.FAILED);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).contains(errorPart);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    // ---------------------------------------------------------------- lỗi tạm thời: lùi 1, 2, 4, 8 phút, lần 5 FAILED

    @ParameterizedTest(name = "attempts={0} → lùi {1} phút")
    @CsvSource({"0, 1", "1, 2", "2, 4", "3, 8"})
    void transientErrorBacksOffExponentially(short attemptsBefore, long minutes) {
        NotificationOutbox row = due(otpRow());
        ReflectionTestUtils.setField(row, "attempts", attemptsBefore);
        doThrow(new MailSendException("451 try again later")).when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.RETRY);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo((short) (attemptsBefore + 1));
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(minutes)));
        assertThat(row.getLastError()).contains("451 try again later");
        assertThat(row.getPayload()).containsKey("ma_otp");
    }

    @Test
    void fifthTransientFailureMarksFailedAndScrubs() {
        NotificationOutbox row = due(otpRow());
        ReflectionTestUtils.setField(row, "attempts", (short) 4);
        doThrow(new MailSendException("451 try again later")).when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.FAILED);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 5);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    // ---------------------------------------------------------------- lỗi mức máy chủ: dừng lượt

    @Test
    void connectionRefusedSchedulesRetryAndStopsRun() {
        NotificationOutbox row = due(otpRow());
        doThrow(new MailSendException("Mail server connection failed",
                new MessagingException("connect", new ConnectException("Connection refused"))))
                .when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.STOP_RUN);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(row.getLastError()).contains("ConnectException");
    }

    @Test
    void readTimeoutInsidePerMessageFailureStopsRun() {
        NotificationOutbox row = due(otpRow());
        Map<Object, Exception> failed = new HashMap<>();
        failed.put(new Object(), new MessagingException("read", new SocketTimeoutException("Read timed out")));
        doThrow(new MailSendException(failed)).when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.STOP_RUN);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void authenticationFailureStopsRun() {
        due(otpRow());
        doThrow(new MailAuthenticationException("535 bad credentials")).when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.STOP_RUN);
    }

    /** Hết lượt đúng lúc SMTP sập: vẫn FAILED, vẫn dừng lượt. */
    @Test
    void connectionFailureOnLastAttemptFailsAndStopsRun() {
        NotificationOutbox row = due(otpRow());
        ReflectionTestUtils.setField(row, "attempts", (short) 4);
        doThrow(new MailSendException("Mail server connection failed",
                new MessagingException("connect", new ConnectException("refused"))))
                .when(mailSender).send(any(MimeMessage.class));

        assertThat(dispatch()).isEqualTo(DispatchResult.STOP_RUN);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    // ---------------------------------------------------------------- lỗi DB: thoát ra để rollback

    @Test
    void databaseErrorPropagatesAndLeavesRowUntouched() {
        NotificationOutbox row = due(otpRow());
        when(templates.findTemplate("OTP_REGISTER")).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(this::dispatch).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // ---------------------------------------------------------------- luồng NORMAL theo lô (docs/adr/0017)

    private static final Duration SEND_BUDGET = Duration.ofSeconds(10);
    private static final TemplateView NOTICE = new TemplateView("IT_NOTICE", "EMAIL", "Thông báo {so}",
            "Nội dung {so}", List.of("so"), List.of("so"));

    private static NotificationOutbox noticeRow(long id, String email) {
        NotificationOutbox row = NotificationOutbox.pending(Channel.EMAIL, "IT_NOTICE", email, null,
                Map.of("so", id), NOW.minusSeconds(30));
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private List<NotificationOutbox> dueBatch(NotificationOutbox... rows) {
        when(templates.findTemplate("IT_NOTICE")).thenReturn(Optional.of(NOTICE));
        when(outbox.lockNextDueEmailBatchNotIn(NOW, DeliveryLane.HIGH_TEMPLATES, 20)).thenReturn(List.of(rows));
        return List.of(rows);
    }

    private void smtpAnswers(boolean stopRun, Attempt... attempts) {
        when(batchSender.send(anyList(), eq(SEND_BUDGET), eq(DeliveryLane.NORMAL)))
                .thenReturn(new BatchResult(List.of(attempts), stopRun));
    }

    private BatchOutcome dispatchBatch() {
        return service.dispatchNextBatch(NOW, POLICY, 20, SEND_BUDGET);
    }

    @SuppressWarnings("unchecked")
    private List<MimeMessage> batchMessages() {
        ArgumentCaptor<List<MimeMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(batchSender).send(captor.capture(), eq(SEND_BUDGET), eq(DeliveryLane.NORMAL));
        return captor.getValue();
    }

    private static Attempt failedWith(Exception error) {
        return new Attempt(Outcome.FAILED, error);
    }

    @Test
    void batchLimitsIdleInTransactionBeforeLockingUpToBatchSize() {
        when(outbox.lockNextDueEmailBatchNotIn(any(), anyCollection(), anyInt())).thenReturn(List.of());

        assertThat(dispatchBatch()).isEqualTo(BatchOutcome.NONE);

        InOrder order = inOrder(outbox);
        order.verify(outbox).limitIdleInTransaction("60s");
        order.verify(outbox).lockNextDueEmailBatchNotIn(NOW, DeliveryLane.HIGH_TEMPLATES, 20);
        verify(batchSender, never()).send(anyList(), any(), any());
    }

    /**
     * Dòng thiếu email không được gửi (FAILED ngay); các dòng khác đi chung một lô theo đúng thứ tự, mỗi thư giữ
     * {@code Message-ID} theo id dòng; dòng chưa thử (hết ngân sách) giữ nguyên để lượt sau gửi.
     */
    @Test
    void composesEachRowSendsOneBatchAndMarksRowsByOutcome() throws Exception {
        List<NotificationOutbox> rows = dueBatch(noticeRow(1, "a@petcare.test"), noticeRow(2, null),
                noticeRow(3, "c@petcare.test"), noticeRow(4, "d@petcare.test"));
        smtpAnswers(false, Attempt.SENT, Attempt.SENT, Attempt.NOT_ATTEMPTED);

        assertThat(dispatchBatch()).isEqualTo(new BatchOutcome(4, 2, 0, 1, false));

        List<MimeMessage> messages = batchMessages();
        assertThat(messages).hasSize(3);
        for (MimeMessage message : messages) {
            message.saveChanges();
        }
        assertThat(messages).extracting(MimeMessage::getMessageID).containsExactly(
                "<notification-1@petcare.local>", "<notification-3@petcare.local>", "<notification-4@petcare.local>");
        assertThat(rows).extracting(NotificationOutbox::getStatus).containsExactly(OutboxStatus.SENT,
                OutboxStatus.FAILED, OutboxStatus.SENT, OutboxStatus.PENDING);
        assertThat(rows.get(3).getAttempts()).as("chưa thử: không tính lần").isZero();
        assertThat(rows.get(3).getNextAttemptAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(meters.get("notification.outbox.queue.latency").tag("lane", "NORMAL").timer().count())
                .isEqualTo(2);
    }

    /** Không kết nối được: chỉ dòng đầu bị tính một lần thử (như gửi từng thư), các dòng khác nguyên vẹn; dừng lượt. */
    @Test
    void connectionFailureChargesOnlyFirstRowAndStopsRun() {
        List<NotificationOutbox> rows = dueBatch(noticeRow(1, "a@petcare.test"), noticeRow(2, "b@petcare.test"),
                noticeRow(3, "c@petcare.test"));
        smtpAnswers(true, failedWith(new MessagingException("connect", new ConnectException("refused"))),
                Attempt.NOT_ATTEMPTED, Attempt.NOT_ATTEMPTED);

        assertThat(dispatchBatch()).isEqualTo(new BatchOutcome(3, 0, 1, 0, true));

        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(rows.get(0).getAttempts()).isEqualTo((short) 1);
        assertThat(rows.get(0).getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(rows.subList(1, 3)).allSatisfy(row -> {
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.minusSeconds(30));
        });
    }

    @Test
    void rejectedRecipientFailsOnlyThatRow() throws Exception {
        List<NotificationOutbox> rows = dueBatch(noticeRow(1, "a@petcare.test"), noticeRow(2, "b@petcare.test"),
                noticeRow(3, "c@petcare.test"));
        smtpAnswers(false, Attempt.SENT, failedWith(new SendFailedException("550 user unknown", null, null, null,
                new Address[] {new InternetAddress("b@petcare.test")})), Attempt.SENT);

        assertThat(dispatchBatch()).isEqualTo(new BatchOutcome(3, 2, 0, 1, false));

        assertThat(rows).extracting(NotificationOutbox::getStatus).containsExactly(OutboxStatus.SENT,
                OutboxStatus.FAILED, OutboxStatus.SENT);
    }

    /** Lỗi tạm của riêng một thư (4xx): dòng đó lùi lịch theo backoff, lô vẫn tiếp. */
    @Test
    void transientErrorOfOneMessageSchedulesRetry() {
        List<NotificationOutbox> rows = dueBatch(noticeRow(1, "a@petcare.test"), noticeRow(2, "b@petcare.test"));
        smtpAnswers(false, failedWith(new MessagingException("451 try again later")), Attempt.SENT);

        assertThat(dispatchBatch()).isEqualTo(new BatchOutcome(2, 1, 1, 0, false));

        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(rows.get(0).getAttempts()).isEqualTo((short) 1);
        assertThat(rows.get(0).getLastError()).contains("451");
    }

    /** Timeout giữa lô ở lần thử cuối: dòng đó FAILED, các dòng sau chưa thử; lô báo dừng lượt. */
    @Test
    void serverErrorOnLastAttemptFailsThatRowAndKeepsTheRest() {
        NotificationOutbox first = noticeRow(1, "a@petcare.test");
        NotificationOutbox second = noticeRow(2, "b@petcare.test");
        ReflectionTestUtils.setField(second, "attempts", (short) 4);
        NotificationOutbox third = noticeRow(3, "c@petcare.test");
        dueBatch(first, second, third);
        smtpAnswers(true, Attempt.SENT,
                failedWith(new MessagingException("read", new SocketTimeoutException("Read timed out"))),
                Attempt.NOT_ATTEMPTED);

        assertThat(dispatchBatch()).isEqualTo(new BatchOutcome(3, 1, 0, 1, true));

        assertThat(second.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(third.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(third.getAttempts()).isZero();
    }

    @Test
    void databaseErrorInBatchPropagatesBeforeSending() {
        dueBatch(noticeRow(1, "a@petcare.test"));
        when(templates.findTemplate("IT_NOTICE")).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(this::dispatchBatch).isInstanceOf(DataAccessResourceFailureException.class);

        verify(batchSender, never()).send(anyList(), any(), any());
    }

    @Test
    void masksEmailForLogs() {
        assertThat(NotificationDispatchService.mask("an@petcare.test")).isEqualTo("a***@petcare.test");
        assertThat(NotificationDispatchService.mask("a@petcare.test")).isEqualTo("***@petcare.test");
    }
}
