package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.care.service.SmtpBatchSender.BatchResult;
import com.petcare.module.care.service.SmtpBatchSender.Outcome;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Gửi một lô trên một kết nối SMTP (docs/adr/0017): một lần kết nối cho cả lô, dừng đúng lúc khi lỗi cấp máy chủ /
 * mất kết nối / hết ngân sách, gửi tiếp khi lỗi chỉ thuộc một thư.
 */
class SmtpBatchSenderTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);

    private final Transport transport = mock(Transport.class);
    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MessagingException connectFailure;
    private final SmtpBatchSender sender = new SmtpBatchSender(() -> {
        connects.incrementAndGet();
        if (connectFailure != null) {
            throw connectFailure;
        }
        return transport;
    }, meters, nanos::get);

    @BeforeEach
    void setUp() {
        when(transport.isConnected()).thenReturn(true);
    }

    private static List<MimeMessage> messages(int count) throws MessagingException {
        Session session = Session.getInstance(new Properties());
        MimeMessage[] messages = new MimeMessage[count];
        for (int i = 0; i < count; i++) {
            messages[i] = new MimeMessage(session);
            messages[i].setRecipient(Message.RecipientType.TO, new InternetAddress("r" + i + "@petcare.test"));
            messages[i].setText("thư " + i);
        }
        return List.of(messages);
    }

    private BatchResult send(List<MimeMessage> messages) {
        return sender.send(messages, BUDGET, DeliveryLane.NORMAL);
    }

    private static List<Outcome> outcomes(BatchResult result) {
        return result.attempts().stream().map(SmtpBatchSender.Attempt::outcome).toList();
    }

    @Test
    void sendsWholeBatchOverOneConnectionInOrder() throws Exception {
        List<MimeMessage> batch = messages(3);

        BatchResult result = send(batch);

        assertThat(outcomes(result)).containsExactly(Outcome.SENT, Outcome.SENT, Outcome.SENT);
        assertThat(result.stopRun()).isFalse();
        assertThat(connects).hasValue(1);
        InOrder order = inOrder(transport);
        for (MimeMessage message : batch) {
            order.verify(transport).sendMessage(message, message.getAllRecipients());
        }
        order.verify(transport).close();
        assertThat(batch).allSatisfy(message -> assertThat(message.getSentDate()).isNotNull());
        assertThat(meters.get("notification.outbox.smtp.connections").tag("lane", "NORMAL").counter().count())
                .isEqualTo(1.0);
    }

    /** {@code saveChanges()} trước khi gửi: {@code Message-ID} của {@code FixedIdMimeMessage} có trên thư thật gửi đi. */
    @Test
    void savesChangesBeforeSending() throws Exception {
        List<MimeMessage> batch = messages(1);
        doAnswer(inv -> {
            assertThat(((MimeMessage) inv.getArgument(0)).getMessageID()).isNotNull();
            return null;
        }).when(transport).sendMessage(any(), any());

        assertThat(outcomes(send(batch))).containsExactly(Outcome.SENT);
    }

    @Test
    void emptyBatchDoesNotConnect() {
        BatchResult result = send(List.of());

        assertThat(result.attempts()).isEmpty();
        assertThat(connects).hasValue(0);
    }

    /** Không kết nối được: chỉ thư đầu mang lỗi, các thư khác chưa thử; dừng lượt; không đếm kết nối. */
    @Test
    void connectFailureMarksOnlyFirstMessageAndStopsRun() throws Exception {
        connectFailure = new MessagingException("connect", new java.net.ConnectException("refused"));

        BatchResult result = send(messages(3));

        assertThat(outcomes(result)).containsExactly(Outcome.FAILED, Outcome.NOT_ATTEMPTED, Outcome.NOT_ATTEMPTED);
        assertThat(result.attempts().get(0).error()).isSameAs(connectFailure);
        assertThat(result.stopRun()).isTrue();
        assertThat(meters.find("notification.outbox.smtp.connections").counter()).isNull();
    }

    @Test
    void authenticationFailureAtConnectStopsRun() throws Exception {
        connectFailure = new AuthenticationFailedException("535 bad credentials");

        assertThat(send(messages(2)).stopRun()).isTrue();
    }

    @Test
    void rejectedRecipientFailsThatMessageAndContinues() throws Exception {
        List<MimeMessage> batch = messages(3);
        doThrow(new SendFailedException("550 user unknown", null, null, null,
                new Address[] {new InternetAddress("r1@petcare.test")}))
                .when(transport).sendMessage(batch.get(1), batch.get(1).getAllRecipients());

        BatchResult result = send(batch);

        assertThat(outcomes(result)).containsExactly(Outcome.SENT, Outcome.FAILED, Outcome.SENT);
        assertThat(result.stopRun()).isFalse();
    }

    /** Timeout ở thư 2: không gửi tiếp (mỗi thư sau có thể chờ thêm một timeout), dừng lượt. */
    @Test
    void serverLevelErrorStopsBatchAndLeavesRestNotAttempted() throws Exception {
        List<MimeMessage> batch = messages(3);
        doThrow(new MessagingException("read", new SocketTimeoutException("Read timed out")))
                .when(transport).sendMessage(batch.get(1), batch.get(1).getAllRecipients());

        BatchResult result = send(batch);

        assertThat(outcomes(result)).containsExactly(Outcome.SENT, Outcome.FAILED, Outcome.NOT_ATTEMPTED);
        assertThat(result.stopRun()).isTrue();
        verify(transport, never()).sendMessage(batch.get(2), batch.get(2).getAllRecipients());
        verify(transport).close();
    }

    /** Lỗi riêng thư nhưng sau đó kết nối đã mất: không gửi tiếp trên kết nối chết, dừng lượt. */
    @Test
    void lostConnectionAfterMessageErrorStopsBatch() throws Exception {
        List<MimeMessage> batch = messages(3);
        doThrow(new MessagingException("421 closing connection"))
                .when(transport).sendMessage(batch.get(1), batch.get(1).getAllRecipients());
        when(transport.isConnected()).thenReturn(false);

        BatchResult result = send(batch);

        assertThat(outcomes(result)).containsExactly(Outcome.SENT, Outcome.FAILED, Outcome.NOT_ATTEMPTED);
        assertThat(result.stopRun()).isTrue();
    }

    /** Ngân sách 10 s tính từ trước khi kết nối; mỗi thư 6 s → thư 1 ở 0 s, thư 2 ở 6 s, thư 3 (12 s) không gửi. */
    @Test
    void stopsBeforeNextMessageWhenBudgetIsSpentWithoutStoppingRun() throws Exception {
        doAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(6).toNanos());
            return null;
        }).when(transport).sendMessage(any(), any());

        BatchResult result = send(messages(3));

        assertThat(outcomes(result)).containsExactly(Outcome.SENT, Outcome.SENT, Outcome.NOT_ATTEMPTED);
        assertThat(result.stopRun()).isFalse();
        verify(transport).close();
    }

    @Test
    void closeFailureIsIgnored() throws Exception {
        doThrow(new MessagingException("QUIT timed out")).when(transport).close();

        assertThat(outcomes(send(messages(1)))).containsExactly(Outcome.SENT);
    }
}
