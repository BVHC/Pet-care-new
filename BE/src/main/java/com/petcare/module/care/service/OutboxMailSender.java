package com.petcare.module.care.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.NotificationDispatchService.ClaimedMail;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;

/**
 * Bước gửi của ST20 (docs/adr/0154): gửi các thư đã nhận bằng <b>một</b> lời gọi
 * {@link JavaMailSender#send(MimeMessage...)} (Spring dùng một kết nối SMTP cho cả lô) và gán kết quả cho từng thư.
 * <b>Không chạy trong transaction</b>: dòng outbox đã được nhận và commit trước, nên SMTP chậm hay treo không giữ khóa.
 * <ul>
 * <li>thư không có trong {@link MailSendException#getFailedMessages()} → {@link Outcome#SENT};</li>
 * <li>lỗi cấp máy chủ (kết nối, timeout, xác thực — {@link SmtpFailures#isServerLevel}): chỉ thư lỗi đầu tiên là
 * {@link Outcome#FAILED} (tốn một lần thử như gửi từng thư), các thư lỗi cấp máy chủ sau nó là
 * {@link Outcome#NOT_ATTEMPTED}; lượt nên dừng;</li>
 * <li>lỗi khác của riêng thư → {@link Outcome#FAILED}; người nhận bị từ chối hay lỗi tạm do bước ghi kết quả phân loại.</li>
 * </ul>
 * Lỗi không kèm danh sách thư (ví dụ sai xác thực lúc kết nối lại giữa lô) được coi là lỗi của mọi thư trong lô: thư đã
 * tới máy chủ trước đó sẽ được gửi lại cùng {@code Message-ID} (at-least-once, docs/adr/0012 mục 4).
 */
@Component
public class OutboxMailSender {

    public enum Outcome { SENT, FAILED, NOT_ATTEMPTED }

    /** Kết quả gửi của một thư; {@code error} chỉ có khi {@link Outcome#FAILED}. */
    public record Delivery(ClaimedMail mail, Outcome outcome, Exception error) {
    }

    /** Kết quả theo đúng thứ tự thư truyền vào; {@code stopRun}: có lỗi cấp máy chủ, lượt chạy nên dừng. */
    public record SendReport(List<Delivery> deliveries, boolean stopRun) {
        public static final SendReport EMPTY = new SendReport(List.of(), false);
    }

    private final JavaMailSender mailSender;
    private final MeterRegistry meters;

    public OutboxMailSender(JavaMailSender mailSender, MeterRegistry meters) {
        this.mailSender = mailSender;
        this.meters = meters;
    }

    public SendReport send(List<ClaimedMail> mails, DeliveryLane lane) {
        if (mails.isEmpty()) {
            return SendReport.EMPTY;
        }
        MimeMessage[] messages = mails.stream().map(ClaimedMail::message).toArray(MimeMessage[]::new);
        meters.counter("notification.outbox.smtp.batches", "lane", lane.name()).increment();
        Map<Object, Exception> failures = Map.of();
        Exception wholeBatch = null;
        try {
            mailSender.send(messages);
        } catch (MailSendException e) {
            failures = e.getFailedMessages();
            if (failures.isEmpty()) {
                wholeBatch = e;
            }
        } catch (RuntimeException e) {
            wholeBatch = e;
        }

        List<Delivery> deliveries = new ArrayList<>(mails.size());
        boolean serverErrorCharged = false;
        boolean stopRun = false;
        for (ClaimedMail mail : mails) {
            Exception error = wholeBatch != null ? wholeBatch : failures.get(mail.message());
            if (error == null) {
                deliveries.add(new Delivery(mail, Outcome.SENT, null));
                continue;
            }
            if (SmtpFailures.isServerLevel(error)) {
                stopRun = true;
                if (serverErrorCharged) {
                    deliveries.add(new Delivery(mail, Outcome.NOT_ATTEMPTED, null));
                    continue;
                }
                serverErrorCharged = true;
            }
            deliveries.add(new Delivery(mail, Outcome.FAILED, error));
        }
        return new SendReport(List.copyOf(deliveries), stopRun);
    }
}
