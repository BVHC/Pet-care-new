package com.petcare.module.care.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.service.NotificationTemplateRenderer.Rendered;
import com.petcare.module.care.service.SmtpBatchSender.Attempt;
import com.petcare.module.care.service.SmtpBatchSender.BatchResult;
import com.petcare.module.care.service.SmtpBatchSender.Outcome;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Gửi email từ {@code notification_outbox} — ST20 (docs/adr/0012). Luồng HIGH: mỗi lời gọi {@link #dispatchNext} là
 * một transaction cho đúng một dòng: khóa dòng đến hạn ({@code SKIP LOCKED}) → dựng thư → SMTP → cập nhật trạng thái →
 * commit. Luồng NORMAL: {@link #dispatchNextBatch} khóa tối đa N dòng và gửi trên một kết nối SMTP trong một
 * transaction (docs/adr/0017). At-least-once: SMTP đã nhận mà commit lỗi thì lượt sau gửi lại, cùng {@code Message-ID}.
 * <ul>
 * <li>Lỗi vĩnh viễn (không có địa chỉ, mẫu không có / sai kênh, thiếu biến bắt buộc, địa chỉ sai, máy chủ từ chối
 * người nhận) → {@code FAILED} ngay.</li>
 * <li>Lỗi tạm thời → lùi {@code initialBackoff × 2^attempts}; tới {@code maxAttempts} thì {@code FAILED}. Lỗi mức kết
 * nối / xác thực trả {@link DispatchResult#STOP_RUN} để lượt chạy dừng thay vì chờ timeout với từng dòng.</li>
 * <li>Lỗi DB thoát ra ngoài: transaction rollback, dòng giữ nguyên, lượt sau làm lại.</li>
 * </ul>
 * Không log payload; email chỉ log dạng che.
 */
@Slf4j
@Service
public class NotificationDispatchService {

    public enum DispatchResult { NONE, SENT, RETRY, FAILED, STOP_RUN }

    /** Tham số vận hành lấy từ {@code app.jobs.notification-outbox} (job truyền vào). */
    public record DispatchPolicy(int maxAttempts, Duration initialBackoff, String mailFrom) {}

    /**
     * Kết quả một lô NORMAL: {@code locked} dòng đã khóa ({@code 0} = hết dòng đến hạn), số dòng gửi được / hẹn thử lại /
     * {@code FAILED}; dòng chưa thử (hết ngân sách, sau lỗi máy chủ) không đổi. {@code stopRun}: lỗi cấp máy chủ.
     */
    public record BatchOutcome(int locked, int sent, int retried, int failed, boolean stopRun) {
        public static final BatchOutcome NONE = new BatchOutcome(0, 0, 0, 0, false);
    }

    /**
     * Postgres tự hủy transaction "idle in transaction" quá lâu (đang chờ SMTP). Lớn hơn tổng timeout SMTP
     * (kết nối 5 s + đọc 10 s + ghi 10 s) để không hủy nhầm lần gửi đã thành công (docs/adr/0012).
     */
    static final String IDLE_IN_TRANSACTION_TIMEOUT = "60s";
    static final String MESSAGE_ID_DOMAIN = "petcare.local";

    private final NotificationOutboxRepository outbox;
    private final NotificationTemplateQueryApi templates;
    private final NotificationTemplateRenderer renderer;
    private final JavaMailSender mailSender;
    private final SmtpBatchSender batchSender;
    private final MeterRegistry meters;

    public NotificationDispatchService(NotificationOutboxRepository outbox, NotificationTemplateQueryApi templates,
            NotificationTemplateRenderer renderer, JavaMailSender mailSender, SmtpBatchSender batchSender,
            MeterRegistry meters) {
        this.outbox = outbox;
        this.templates = templates;
        this.renderer = renderer;
        this.mailSender = mailSender;
        this.batchSender = batchSender;
        this.meters = meters;
    }

    @Transactional
    public DispatchResult dispatchNext(DeliveryLane lane, Instant now, DispatchPolicy policy) {
        requireEmailLane(lane);
        outbox.limitIdleInTransaction(IDLE_IN_TRANSACTION_TIMEOUT);
        Optional<NotificationOutbox> next = lane == DeliveryLane.HIGH
                ? outbox.lockNextDueEmailIn(now, DeliveryLane.HIGH_TEMPLATES)
                : outbox.lockNextDueEmailNotIn(now, DeliveryLane.HIGH_TEMPLATES);
        if (next.isEmpty()) {
            return DispatchResult.NONE;
        }
        NotificationOutbox row = next.get();
        boolean firstAttempt = row.getAttempts() == 0;
        Instant dueAt = row.getNextAttemptAt();

        MimeMessage message;
        try {
            message = compose(row, policy);
        } catch (PermanentFailure e) {
            return fail(row, lane, e.getMessage());
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            return retryOrFail(row, lane, now, policy, SmtpFailures.describe(e));
        }

        try {
            mailSender.send(message);
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            return handleSendFailure(row, lane, now, policy, e);
        }

        return markSent(row, lane, now, firstAttempt, dueAt);
    }

    /**
     * Luồng NORMAL theo lô (docs/adr/0017): một transaction khóa tối đa {@code batchSize} dòng đến hạn
     * ({@code SKIP LOCKED}), dựng thư từng dòng, gửi cả lô trên một kết nối SMTP ({@link SmtpBatchSender}, dừng khi hết
     * {@code sendBudget}) rồi cập nhật từng dòng. Lỗi kết nối chỉ tính một lần thử cho dòng đầu (như gửi từng thư);
     * dòng chưa thử giữ nguyên để lượt sau gửi.
     */
    @Transactional
    public BatchOutcome dispatchNextBatch(Instant now, DispatchPolicy policy, int batchSize, Duration sendBudget) {
        DeliveryLane lane = DeliveryLane.NORMAL;
        outbox.limitIdleInTransaction(IDLE_IN_TRANSACTION_TIMEOUT);
        List<NotificationOutbox> rows = outbox.lockNextDueEmailBatchNotIn(now, DeliveryLane.HIGH_TEMPLATES, batchSize);
        if (rows.isEmpty()) {
            return BatchOutcome.NONE;
        }
        int[] tally = new int[DispatchResult.values().length];

        List<NotificationOutbox> sendable = new ArrayList<>();
        List<MimeMessage> messages = new ArrayList<>();
        for (NotificationOutbox row : rows) {
            try {
                messages.add(compose(row, policy));
                sendable.add(row);
            } catch (PermanentFailure e) {
                tally[fail(row, lane, e.getMessage()).ordinal()]++;
            } catch (DataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                tally[retryOrFail(row, lane, now, policy, SmtpFailures.describe(e)).ordinal()]++;
            }
        }

        BatchResult sent = batchSender.send(messages, sendBudget, lane);
        for (int i = 0; i < sendable.size(); i++) {
            NotificationOutbox row = sendable.get(i);
            Attempt attempt = sent.attempts().get(i);
            if (attempt.outcome() == Outcome.SENT) {
                tally[markSent(row, lane, now, row.getAttempts() == 0, row.getNextAttemptAt()).ordinal()]++;
            } else if (attempt.outcome() == Outcome.FAILED) {
                Exception error = attempt.error();
                DispatchResult result = SmtpFailures.isRejectedRecipient(error)
                        ? fail(row, lane, SmtpFailures.describe(error))
                        : retryOrFail(row, lane, now, policy, SmtpFailures.describe(error));
                tally[result.ordinal()]++;
            }
        }
        return new BatchOutcome(rows.size(), tally[DispatchResult.SENT.ordinal()],
                tally[DispatchResult.RETRY.ordinal()], tally[DispatchResult.FAILED.ordinal()], sent.stopRun());
    }

    /** Số dòng đến hạn chưa gửi của luồng — cho metrics và cảnh báo trễ (không khóa). */
    @Transactional(readOnly = true)
    public LaneBacklog backlog(DeliveryLane lane, Instant now) {
        requireEmailLane(lane);
        return lane == DeliveryLane.HIGH
                ? outbox.backlogIn(now, DeliveryLane.HIGH_TEMPLATES, OutboxStatus.PENDING, Channel.EMAIL)
                : outbox.backlogNotIn(now, DeliveryLane.HIGH_TEMPLATES, OutboxStatus.PENDING, Channel.EMAIL);
    }

    private MimeMessage compose(NotificationOutbox row, DispatchPolicy policy) throws PermanentFailure {
        String to = row.getRecipientEmail();
        if (to == null || to.isBlank()) {
            throw new PermanentFailure("Thiếu recipient_email cho kênh EMAIL");
        }
        TemplateView template = templates.findTemplate(row.getTemplateCode())
                .orElseThrow(() -> new PermanentFailure("Không có mẫu " + row.getTemplateCode()));
        if (!Channel.EMAIL.name().equals(template.channel())) {
            throw new PermanentFailure("Mẫu " + template.code() + " thuộc kênh " + template.channel());
        }
        List<String> missing = renderer.missingRequired(template, row.getPayload());
        if (!missing.isEmpty()) {
            throw new PermanentFailure("Thiếu biến bắt buộc " + missing + " của mẫu " + template.code());
        }
        Rendered rendered = renderer.render(template, row.getPayload());
        try {
            Session session = mailSender.createMimeMessage().getSession();
            MimeMessage message = new FixedIdMimeMessage(session, messageId(row.getId()));
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(policy.mailFrom(), true));
            helper.setTo(new InternetAddress(to, true));
            helper.setSubject(rendered.subject());
            helper.setText(rendered.body(), false);
            return message;
        } catch (MessagingException e) {
            throw new PermanentFailure("Không dựng được thư: " + e.getMessage());
        }
    }

    private DispatchResult handleSendFailure(NotificationOutbox row, DeliveryLane lane, Instant now,
            DispatchPolicy policy, RuntimeException error) {
        if (SmtpFailures.isRejectedRecipient(error)) {
            return fail(row, lane, SmtpFailures.describe(error));
        }
        DispatchResult result = retryOrFail(row, lane, now, policy, SmtpFailures.describe(error));
        return SmtpFailures.isServerLevel(error) ? DispatchResult.STOP_RUN : result;
    }

    private DispatchResult markSent(NotificationOutbox row, DeliveryLane lane, Instant now, boolean firstAttempt,
            Instant dueAt) {
        row.markSent(now);
        if (firstAttempt) {
            Duration waited = Duration.between(dueAt, now);
            meters.timer("notification.outbox.queue.latency", "lane", lane.name())
                    .record(waited.isNegative() ? Duration.ZERO : waited);
        }
        log.debug("NOTIFICATION_SENT id={} template={} lane={} to={}", row.getId(), row.getTemplateCode(), lane,
                mask(row.getRecipientEmail()));
        return DispatchResult.SENT;
    }

    private DispatchResult retryOrFail(NotificationOutbox row, DeliveryLane lane, Instant now, DispatchPolicy policy,
            String error) {
        if (row.getAttempts() + 1 >= policy.maxAttempts()) {
            return fail(row, lane, error);
        }
        Instant nextAttempt = now.plus(policy.initialBackoff().multipliedBy(1L << row.getAttempts()));
        row.scheduleRetry(nextAttempt, error);
        log.warn("NOTIFICATION_RETRY id={} template={} lane={} attempts={} next={} error={}", row.getId(),
                row.getTemplateCode(), lane, row.getAttempts(), nextAttempt, error);
        return DispatchResult.RETRY;
    }

    private DispatchResult fail(NotificationOutbox row, DeliveryLane lane, String error) {
        row.markFailed(error);
        log.warn("NOTIFICATION_FAILED id={} template={} lane={} attempts={} error={}", row.getId(),
                row.getTemplateCode(), lane, row.getAttempts(), error);
        return DispatchResult.FAILED;
    }

    /** Luồng IN_APP do {@link InAppNotificationDeliveryService} (docs/adr/0014); gọi nhầm ở đây là lỗi lập trình. */
    private static void requireEmailLane(DeliveryLane lane) {
        if (!lane.isEmail()) {
            throw new IllegalArgumentException("NotificationDispatchService chỉ xử lý luồng email, không xử lý " + lane);
        }
    }

    static String messageId(Long outboxId) {
        return "<notification-" + outboxId + "@" + MESSAGE_ID_DOMAIN + ">";
    }

    static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }

    /** Lỗi không thể khỏi khi thử lại; chỉ dùng trong lớp này. */
    private static final class PermanentFailure extends Exception {
        PermanentFailure(String message) {
            super(message, null, false, false);
        }
    }

    /** {@code Message-ID} cố định theo dòng outbox: gửi lại vẫn cùng id, client gộp thư trùng. */
    private static final class FixedIdMimeMessage extends MimeMessage {
        private final String messageId;

        FixedIdMimeMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }
}
