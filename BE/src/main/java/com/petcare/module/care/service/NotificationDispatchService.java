package com.petcare.module.care.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.petcare.module.care.service.OutboxMailSender.Delivery;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Phần CSDL của ST20 email (docs/adr/0012, docs/adr/0154): <b>nhận dòng</b> và <b>ghi kết quả</b>, mỗi bước một
 * transaction ngắn; SMTP chạy giữa hai bước, ngoài transaction ({@link OutboxMailSender}).
 * <ol>
 * <li>Nhận dòng: khóa dòng đến hạn ({@code SKIP LOCKED}) → dựng thư → đặt {@code next_attempt_at} = hạn giữ (vé) →
 * commit. Lượt khác không thấy dòng tới hạn giữ; chết giữa chừng thì dòng tự hiện lại khi hết hạn.</li>
 * <li>Ghi kết quả: khóa từng dòng theo {@code id} tăng dần, kiểm lại vé dưới khóa, rồi {@code SENT} / thử lại /
 * {@code FAILED} / trả về hạn cũ. Vé lệch (đã hết hạn giữ, lượt khác nhận lại) thì bỏ qua.</li>
 * </ol>
 * At-least-once: SMTP đã nhận mà ghi kết quả không xong thì thư được gửi lại sau hạn giữ, cùng {@code Message-ID}.
 * <ul>
 * <li>Lỗi vĩnh viễn (không có địa chỉ, mẫu không có / sai kênh, thiếu biến bắt buộc, địa chỉ sai, máy chủ từ chối
 * người nhận) → {@code FAILED} ngay.</li>
 * <li>Lỗi tạm thời → lùi {@code initialBackoff × 2^attempts}; tới {@code maxAttempts} thì {@code FAILED}.</li>
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
     * Một thư đã nhận, chờ gửi ngoài transaction. {@code claimToken}: giá trị {@code next_attempt_at} đã đặt lúc nhận
     * (vé); {@code dueAt}: hạn trước khi nhận, để trả dòng về hoặc đo độ trễ hàng đợi ở lần gửi đầu.
     */
    public record ClaimedMail(Long outboxId, Instant claimToken, Instant dueAt, boolean firstAttempt,
            MimeMessage message) {}

    /**
     * Kết quả bước nhận: {@code locked} dòng đã khóa ({@code 0} = hết dòng đến hạn), các thư cần gửi, và số dòng đã
     * xử lý ngay vì dựng thư lỗi ({@code retried} hẹn thử lại, {@code failed} thành {@code FAILED}).
     */
    public record Claim(int locked, List<ClaimedMail> mails, int retried, int failed) {
        public static final Claim NONE = new Claim(0, List.of(), 0, 0);
    }

    /** Kết quả bước ghi; {@code stale}: dòng có vé đã hết hiệu lực, không đổi gì. */
    public record Settled(int sent, int retried, int failed, int stale) {}

    /**
     * Postgres tự hủy transaction "idle in transaction" quá lâu. SMTP đã ra ngoài transaction (docs/adr/0154); giới hạn
     * này còn là lưới an toàn cho transaction nhận dòng.
     */
    static final String IDLE_IN_TRANSACTION_TIMEOUT = "60s";
    static final String MESSAGE_ID_DOMAIN = "petcare.local";

    private final NotificationOutboxRepository outbox;
    private final NotificationTemplateQueryApi templates;
    private final NotificationTemplateRenderer renderer;
    private final JavaMailSender mailSender;
    private final MeterRegistry meters;

    public NotificationDispatchService(NotificationOutboxRepository outbox, NotificationTemplateQueryApi templates,
            NotificationTemplateRenderer renderer, JavaMailSender mailSender, MeterRegistry meters) {
        this.outbox = outbox;
        this.templates = templates;
        this.renderer = renderer;
        this.mailSender = mailSender;
        this.meters = meters;
    }

    /** Luồng HIGH: nhận một dòng đến hạn (OTP, mật khẩu tạm), giữ trong {@code lease}. */
    @Transactional
    public Claim claimNextHigh(Instant now, DispatchPolicy policy, Duration lease) {
        outbox.limitIdleInTransaction(IDLE_IN_TRANSACTION_TIMEOUT);
        List<NotificationOutbox> rows = outbox.lockNextDueEmailIn(now, DeliveryLane.HIGH_TEMPLATES).stream().toList();
        return claim(rows, DeliveryLane.HIGH, now, policy, lease);
    }

    /** Luồng NORMAL theo lô (docs/adr/0017): nhận tối đa {@code batchSize} dòng đến hạn, giữ trong {@code lease}. */
    @Transactional
    public Claim claimNextNormalBatch(Instant now, DispatchPolicy policy, int batchSize, Duration lease) {
        outbox.limitIdleInTransaction(IDLE_IN_TRANSACTION_TIMEOUT);
        List<NotificationOutbox> rows = outbox.lockNextDueEmailBatchNotIn(now, DeliveryLane.HIGH_TEMPLATES, batchSize);
        return claim(rows, DeliveryLane.NORMAL, now, policy, lease);
    }

    /**
     * Kích gửi ngay sau commit (docs/adr/0154): nhận đúng dòng HIGH {@code outboxId} nếu nó còn đến hạn; dòng job HIGH
     * đã nhận, đang bị khóa hay đã xử lý thì trả {@link Claim#NONE}.
     */
    @Transactional
    public Claim claimHighById(Long outboxId, Instant now, DispatchPolicy policy, Duration lease) {
        outbox.limitIdleInTransaction(IDLE_IN_TRANSACTION_TIMEOUT);
        List<NotificationOutbox> rows = outbox.lockDueEmailById(outboxId, now, DeliveryLane.HIGH_TEMPLATES).stream()
                .toList();
        return claim(rows, DeliveryLane.HIGH, now, policy, lease);
    }

    /** Ghi kết quả gửi; khóa theo {@code id} tăng dần để hai lượt ghi không chờ chéo nhau. */
    @Transactional
    public Settled settle(DeliveryLane lane, Instant now, DispatchPolicy policy, List<Delivery> deliveries) {
        requireEmailLane(lane);
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int stale = 0;
        List<Delivery> ordered = deliveries.stream().sorted(Comparator.comparing(d -> d.mail().outboxId())).toList();
        for (Delivery delivery : ordered) {
            ClaimedMail mail = delivery.mail();
            Optional<NotificationOutbox> locked = outbox.findByIdForUpdate(mail.outboxId());
            if (locked.isEmpty() || !locked.get().isClaimedBy(mail.claimToken())) {
                stale++;
                log.warn("NOTIFICATION_SETTLE_STALE id={} lane={} outcome={}", mail.outboxId(), lane,
                        delivery.outcome());
                continue;
            }
            NotificationOutbox row = locked.get();
            switch (delivery.outcome()) {
                case SENT -> {
                    markSent(row, lane, now, mail.firstAttempt(), mail.dueAt());
                    sent++;
                }
                case NOT_ATTEMPTED -> row.release(mail.dueAt());
                case FAILED -> {
                    Exception error = delivery.error();
                    DispatchResult result = SmtpFailures.isRejectedRecipient(error)
                            ? fail(row, lane, SmtpFailures.describe(error))
                            : retryOrFail(row, lane, now, policy, SmtpFailures.describe(error));
                    if (result == DispatchResult.RETRY) {
                        retried++;
                    } else {
                        failed++;
                    }
                }
            }
        }
        return new Settled(sent, retried, failed, stale);
    }

    /** Số dòng đến hạn chưa gửi của luồng — cho metrics và cảnh báo trễ (không khóa). */
    @Transactional(readOnly = true)
    public LaneBacklog backlog(DeliveryLane lane, Instant now) {
        requireEmailLane(lane);
        return lane == DeliveryLane.HIGH
                ? outbox.backlogIn(now, DeliveryLane.HIGH_TEMPLATES, OutboxStatus.PENDING, Channel.EMAIL)
                : outbox.backlogNotIn(now, DeliveryLane.HIGH_TEMPLATES, OutboxStatus.PENDING, Channel.EMAIL);
    }

    /**
     * Dựng thư từng dòng; dòng dựng được thì nhận với cùng một vé {@code now + lease} (cắt về micro giây như cột
     * {@code timestamptz}, để vé đọc lại từ DB bằng đúng giá trị đã ghi).
     */
    private Claim claim(List<NotificationOutbox> rows, DeliveryLane lane, Instant now, DispatchPolicy policy,
            Duration lease) {
        if (rows.isEmpty()) {
            return Claim.NONE;
        }
        Instant token = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
        List<ClaimedMail> mails = new ArrayList<>();
        int retried = 0;
        int failed = 0;
        for (NotificationOutbox row : rows) {
            MimeMessage message;
            try {
                message = compose(row, policy);
            } catch (PermanentFailure e) {
                fail(row, lane, e.getMessage());
                failed++;
                continue;
            } catch (DataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                if (retryOrFail(row, lane, now, policy, SmtpFailures.describe(e)) == DispatchResult.RETRY) {
                    retried++;
                } else {
                    failed++;
                }
                continue;
            }
            mails.add(new ClaimedMail(row.getId(), token, row.getNextAttemptAt(), row.getAttempts() == 0, message));
            row.claim(token);
        }
        return new Claim(rows.size(), List.copyOf(mails), retried, failed);
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
