package com.petcare.module.care.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Gửi một lô thư trên <b>một</b> kết nối SMTP (docs/adr/0017, trả nợ D008). Tự quản {@link Transport} thay vì
 * {@code JavaMailSender.send(MimeMessage...)} vì hàm đó gửi tiếp sau lỗi timeout — một lô có thể chờ N × timeout,
 * vượt {@code idle_in_transaction_session_timeout} của transaction đang giữ khóa dòng outbox. Ở đây:
 * <ul>
 * <li>không kết nối được → thư đầu nhận lỗi kết nối, các thư sau {@link Outcome#NOT_ATTEMPTED}, dừng lượt;</li>
 * <li>lỗi cấp máy chủ ở thư i (timeout, xác thực) hoặc mất kết nối sau lỗi → thư i lỗi, các thư sau chưa thử, dừng
 * lượt;</li>
 * <li>lỗi riêng thư (người nhận bị từ chối, 4xx) → thư đó lỗi, gửi tiếp;</li>
 * <li>hết {@code budget} (tính cả lúc kết nối) trước thư i → các thư từ i chưa thử, không dừng lượt.</li>
 * </ul>
 * Mỗi thư được {@code saveChanges()} trước khi gửi để giữ {@code Message-ID} cố định (như {@code JavaMailSenderImpl}).
 * Đếm kết nối mở được ở {@code notification.outbox.smtp.connections}.
 */
@Slf4j
@Component
public class SmtpBatchSender {

    public enum Outcome { SENT, FAILED, NOT_ATTEMPTED }

    /** Kết quả của thư thứ i; {@code error} chỉ có khi {@link Outcome#FAILED}. */
    public record Attempt(Outcome outcome, Exception error) {
        static final Attempt SENT = new Attempt(Outcome.SENT, null);
        static final Attempt NOT_ATTEMPTED = new Attempt(Outcome.NOT_ATTEMPTED, null);
    }

    /** Kết quả theo đúng thứ tự thư truyền vào; {@code stopRun}: lỗi cấp máy chủ, lượt chạy nên dừng. */
    public record BatchResult(List<Attempt> attempts, boolean stopRun) {
    }

    /** Mở kết nối SMTP; tách ra để unit test thay bằng {@code Transport} giả. */
    interface TransportFactory {
        Transport connect() throws MessagingException;
    }

    private final TransportFactory transports;
    private final MeterRegistry meters;
    private final LongSupplier ticker;

    @Autowired
    public SmtpBatchSender(JavaMailSenderImpl mailSender, MeterRegistry meters) {
        this(() -> connect(mailSender), meters, System::nanoTime);
    }

    SmtpBatchSender(TransportFactory transports, MeterRegistry meters, LongSupplier ticker) {
        this.transports = transports;
        this.meters = meters;
        this.ticker = ticker;
    }

    public BatchResult send(List<MimeMessage> messages, Duration budget, DeliveryLane lane) {
        List<Attempt> attempts = new ArrayList<>(Collections.nCopies(messages.size(), Attempt.NOT_ATTEMPTED));
        if (messages.isEmpty()) {
            return new BatchResult(attempts, false);
        }
        long started = ticker.getAsLong();
        Transport transport;
        try {
            transport = transports.connect();
        } catch (MessagingException | RuntimeException e) {
            attempts.set(0, new Attempt(Outcome.FAILED, e));
            return new BatchResult(attempts, true);
        }
        meters.counter("notification.outbox.smtp.connections", "lane", lane.name()).increment();

        boolean stopRun = false;
        try {
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0 && ticker.getAsLong() - started >= budget.toNanos()) {
                    log.debug("NOTIFICATION_BATCH_BUDGET_SPENT lane={} sent={} remaining={}", lane, i,
                            messages.size() - i);
                    break;
                }
                try {
                    sendOne(transport, messages.get(i));
                    attempts.set(i, Attempt.SENT);
                } catch (MessagingException | RuntimeException e) {
                    attempts.set(i, new Attempt(Outcome.FAILED, e));
                    if (SmtpFailures.isServerLevel(e) || !transport.isConnected()) {
                        stopRun = true;
                        break;
                    }
                }
            }
        } finally {
            close(transport);
        }
        return new BatchResult(attempts, stopRun);
    }

    private static void sendOne(Transport transport, MimeMessage message) throws MessagingException {
        if (message.getSentDate() == null) {
            message.setSentDate(new Date());
        }
        message.saveChanges();
        Address[] recipients = message.getAllRecipients();
        transport.sendMessage(message, recipients != null ? recipients : new Address[0]);
    }

    private static void close(Transport transport) {
        try {
            transport.close();
        } catch (MessagingException | RuntimeException e) {
            log.debug("NOTIFICATION_SMTP_CLOSE_FAILED error={}", e.toString());
        }
    }

    /** Như {@code JavaMailSenderImpl.connectTransport}: đọc cấu hình mỗi lần để đổi cổng/máy chủ có hiệu lực ngay. */
    private static Transport connect(JavaMailSenderImpl mailSender) throws MessagingException {
        String username = mailSender.getUsername();
        String password = mailSender.getPassword();
        if ("".equals(username)) {
            username = null;
            if ("".equals(password)) {
                password = null;
            }
        }
        String protocol = mailSender.getProtocol();
        if (protocol == null) {
            protocol = mailSender.getSession().getProperty("mail.transport.protocol");
        }
        Transport transport = mailSender.getSession().getTransport(protocol != null ? protocol : "smtp");
        transport.connect(mailSender.getHost(), mailSender.getPort(), username, password);
        return transport;
    }
}
