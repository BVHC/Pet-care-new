package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.petcare.module.care.service.NotificationDispatchService.ClaimedMail;
import com.petcare.module.care.service.OutboxMailSender.Delivery;
import com.petcare.module.care.service.OutboxMailSender.Outcome;
import com.petcare.module.care.service.OutboxMailSender.SendReport;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Bước gửi của ST20 (docs/adr/0154): một lời gọi {@code JavaMailSender.send(MimeMessage...)} cho cả lô và ma trận lỗi
 * của docs/adr/0017 mục 5 dựng lại trên {@link MailSendException#getFailedMessages()}.
 */
class OutboxMailSenderTest {

    private static final Instant TOKEN = Instant.parse("2026-10-07T03:01:00Z");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxMailSender sender = new OutboxMailSender(mailSender, meters);

    private static List<ClaimedMail> mails(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new ClaimedMail((long) i, TOKEN, TOKEN, true,
                new MimeMessage(Session.getInstance(new Properties())))).toList();
    }

    /** {@code JavaMailSenderImpl} ném {@link MailSendException} với thư lỗi làm khóa (chính đối tượng thư). */
    private void failWith(List<ClaimedMail> mails, Map<Integer, Exception> errorsByIndex) {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        errorsByIndex.forEach((index, error) -> failed.put(mails.get(index).message(), error));
        doThrow(new MailSendException(failed)).when(mailSender).send(any(MimeMessage[].class));
    }

    private static List<Outcome> outcomes(SendReport report) {
        return report.deliveries().stream().map(Delivery::outcome).toList();
    }

    private static MessagingException timeout() {
        return new MessagingException("read", new SocketTimeoutException("Read timed out"));
    }

    @Test
    void emptyBatchSendsNothing() {
        assertThat(sender.send(List.of(), DeliveryLane.NORMAL)).isEqualTo(SendReport.EMPTY);

        verify(mailSender, never()).send(any(MimeMessage[].class));
    }

    /** Cả lô đi trong một lời gọi (Spring dùng một kết nối SMTP), đúng thứ tự; đếm ở {@code smtp.batches}. */
    @Test
    void sendsWholeBatchInOneCallInOrder() {
        List<ClaimedMail> mails = mails(3);

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        ArgumentCaptor<MimeMessage[]> captor = ArgumentCaptor.forClass(MimeMessage[].class);
        verify(mailSender, times(1)).send(captor.capture());
        assertThat(captor.getValue()).containsExactly(mails.stream().map(ClaimedMail::message)
                .toArray(MimeMessage[]::new));
        assertThat(outcomes(report)).containsOnly(Outcome.SENT);
        assertThat(report.stopRun()).isFalse();
        assertThat(report.deliveries()).extracting(Delivery::mail).containsExactlyElementsOf(mails);
        assertThat(meters.get("notification.outbox.smtp.batches").tag("lane", "NORMAL").counter().count())
                .isEqualTo(1.0);
    }

    /** Không kết nối được: Spring gán lỗi kết nối cho mọi thư; chỉ thư đầu tốn một lần thử, các thư khác chưa thử. */
    @Test
    void connectionFailureChargesOnlyFirstMailAndStopsRun() {
        List<ClaimedMail> mails = mails(3);
        Exception refused = new MessagingException("connect", new ConnectException("refused"));
        failWith(mails, Map.of(0, refused, 1, refused, 2, refused));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.FAILED, Outcome.NOT_ATTEMPTED, Outcome.NOT_ATTEMPTED);
        assertThat(report.deliveries().get(0).error()).isSameAs(refused);
        assertThat(report.stopRun()).isTrue();
    }

    /** Sai xác thực lúc kết nối: lỗi không kèm danh sách thư, coi là lỗi của cả lô. */
    @Test
    void authenticationFailureChargesFirstMailAndStopsRun() {
        List<ClaimedMail> mails = mails(2);
        doThrow(new MailAuthenticationException("535 bad credentials")).when(mailSender)
                .send(any(MimeMessage[].class));

        SendReport report = sender.send(mails, DeliveryLane.HIGH);

        assertThat(outcomes(report)).containsExactly(Outcome.FAILED, Outcome.NOT_ATTEMPTED);
        assertThat(report.stopRun()).isTrue();
    }

    /** Người nhận bị từ chối: chỉ thư đó lỗi, các thư khác đã gửi; không dừng lượt. */
    @Test
    void rejectedRecipientFailsOnlyThatMail() throws Exception {
        List<ClaimedMail> mails = mails(3);
        SendFailedException rejected = new SendFailedException("550 user unknown", null, null, null,
                new Address[] {new InternetAddress("b@petcare.test")});
        failWith(mails, Map.of(1, rejected));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.SENT, Outcome.FAILED, Outcome.SENT);
        assertThat(report.deliveries().get(1).error()).isSameAs(rejected);
        assertThat(report.stopRun()).isFalse();
    }

    /** Lỗi tạm của riêng một thư (4xx): thư đó lỗi, lô vẫn tiếp. */
    @Test
    void transientErrorOfOneMailDoesNotStopRun() {
        List<ClaimedMail> mails = mails(2);
        failWith(mails, Map.of(0, new MessagingException("451 try again later")));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.FAILED, Outcome.SENT);
        assertThat(report.stopRun()).isFalse();
    }

    /**
     * Timeout giữa lô: Spring kết nối lại và gửi tiếp, nên các thư sau có thể cũng timeout. Thư timeout đầu tiên tốn một
     * lần thử, các thư timeout sau chưa tính; thư đã gửi được vẫn {@code SENT}; lượt dừng.
     */
    @Test
    void timeoutMidBatchChargesFirstServerErrorOnly() {
        List<ClaimedMail> mails = mails(4);
        failWith(mails, Map.of(1, timeout(), 3, timeout()));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.SENT, Outcome.FAILED, Outcome.SENT,
                Outcome.NOT_ATTEMPTED);
        assertThat(report.stopRun()).isTrue();
    }

    /** Lỗi riêng thư trước, lỗi máy chủ sau: lỗi máy chủ đầu tiên vẫn tốn một lần thử. */
    @Test
    void perMessageErrorBeforeServerErrorIsChargedSeparately() {
        List<ClaimedMail> mails = mails(3);
        failWith(mails, Map.of(0, new MessagingException("451 later"), 1, timeout(), 2, timeout()));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.FAILED, Outcome.FAILED, Outcome.NOT_ATTEMPTED);
        assertThat(report.stopRun()).isTrue();
    }

    /** Lỗi chạy bất ngờ (không phải MailException) cũng không thoát ra: cả lô lỗi tạm, bước ghi lùi lịch. */
    @Test
    void unexpectedRuntimeErrorFailsWholeBatchWithoutThrowing() {
        List<ClaimedMail> mails = mails(2);
        doThrow(new IllegalStateException("boom")).when(mailSender).send(any(MimeMessage[].class));

        SendReport report = sender.send(mails, DeliveryLane.NORMAL);

        assertThat(outcomes(report)).containsExactly(Outcome.FAILED, Outcome.FAILED);
        assertThat(report.stopRun()).isFalse();
    }
}
