package com.petcare.module.care.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.Notification;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.care.service.NotificationTemplateRenderer.Rendered;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Giao thông báo trong ứng dụng từ {@code notification_outbox} — ST20 luồng IN_APP (docs/adr/0014). Mỗi lời gọi
 * {@link #deliverNext} là một transaction cho đúng một dòng: khóa dòng IN_APP đến hạn ({@code SKIP LOCKED}) → kiểm →
 * render → INSERT {@code notifications} → {@code SENT} → commit. INSERT và {@code SENT} cùng transaction nên mỗi dòng
 * outbox sinh đúng một thông báo (exactly-once), không cần thử lại lũy thừa như email.
 * <ul>
 * <li>Dòng không giao được (thiếu người nhận, mẫu không có / sai kênh / thiếu tiêu đề, thiếu biến bắt buộc, type hoặc
 * link quá dài) → {@code FAILED} ngay. Mọi giới hạn cột của {@code notifications} được kiểm ở đây, trước INSERT: lỗi
 * ràng buộc DB sẽ rollback và dòng đứng đầu hàng chặn cả luồng ở mọi lượt sau.</li>
 * <li>Tiêu đề render dài quá {@link Notification#MAX_TITLE_LENGTH} → cắt, vẫn giao.</li>
 * <li>Lỗi DB thoát ra ngoài: rollback, dòng giữ {@code PENDING}, lượt sau làm lại.</li>
 * </ul>
 * Không đọc {@code accounts.notification_settings} (care-v1 Q1 còn TBD): module gửi quyết định có {@code enqueue} hay
 * không. Không log payload.
 */
@Slf4j
@Service
public class InAppNotificationDeliveryService {

    /** Hậu tố của mã mẫu trong app (06 §8 Q4); {@code notifications.type} là mã bỏ hậu tố (docs/adr/0014). */
    static final String APP_SUFFIX = "_APP";
    static final String ELLIPSIS = "…";

    private final NotificationOutboxRepository outbox;
    private final NotificationRepository notifications;
    private final NotificationTemplateQueryApi templates;
    private final NotificationTemplateRenderer renderer;
    private final MeterRegistry meters;

    public InAppNotificationDeliveryService(NotificationOutboxRepository outbox, NotificationRepository notifications,
            NotificationTemplateQueryApi templates, NotificationTemplateRenderer renderer, MeterRegistry meters) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.templates = templates;
        this.renderer = renderer;
        this.meters = meters;
    }

    @Transactional
    public DispatchResult deliverNext(Instant now) {
        Optional<NotificationOutbox> next = outbox.lockNextDueInApp(now);
        if (next.isEmpty()) {
            return DispatchResult.NONE;
        }
        NotificationOutbox row = next.get();
        Instant dueAt = row.getNextAttemptAt();

        Notification notification;
        try {
            notification = compose(row);
        } catch (Undeliverable e) {
            row.markFailed(e.getMessage());
            log.warn("NOTIFICATION_FAILED id={} template={} lane={} attempts={} error={}", row.getId(),
                    row.getTemplateCode(), DeliveryLane.IN_APP, row.getAttempts(), e.getMessage());
            return DispatchResult.FAILED;
        }

        notifications.save(notification);
        row.markSent(now);
        Duration waited = Duration.between(dueAt, now);
        meters.timer("notification.outbox.queue.latency", "lane", DeliveryLane.IN_APP.name())
                .record(waited.isNegative() ? Duration.ZERO : waited);
        log.debug("NOTIFICATION_DELIVERED id={} template={} accountId={}", row.getId(), row.getTemplateCode(),
                row.getRecipientAccountId());
        return DispatchResult.SENT;
    }

    /** Số dòng IN_APP đến hạn chưa giao và hạn sớm nhất — cho metrics và cảnh báo trễ (không khóa). */
    @Transactional(readOnly = true)
    public LaneBacklog backlog(Instant now) {
        long pending = outbox.countDueInApp(now);
        if (pending == 0) {
            return new LaneBacklog(0, null);
        }
        return new LaneBacklog(pending,
                outbox.findOldestDueInApp(now).map(NotificationOutbox::getNextAttemptAt).orElse(null));
    }

    private Notification compose(NotificationOutbox row) throws Undeliverable {
        Long accountId = row.getRecipientAccountId();
        if (accountId == null) {
            throw new Undeliverable("Thiếu recipient_account_id cho kênh IN_APP");
        }
        TemplateView template = templates.findTemplate(row.getTemplateCode())
                .orElseThrow(() -> new Undeliverable("Không có mẫu " + row.getTemplateCode()));
        if (!Channel.IN_APP.name().equals(template.channel())) {
            throw new Undeliverable("Mẫu " + template.code() + " thuộc kênh " + template.channel());
        }
        List<String> missing = renderer.missingRequired(template, row.getPayload());
        if (!missing.isEmpty()) {
            throw new Undeliverable("Thiếu biến bắt buộc " + missing + " của mẫu " + template.code());
        }
        String type = typeOf(template.code());
        if (length(type) > Notification.MAX_TYPE_LENGTH) {
            throw new Undeliverable("Mã " + type + " dài quá " + Notification.MAX_TYPE_LENGTH + " ký tự");
        }
        Rendered rendered = renderer.render(template, row.getPayload());
        if (rendered.subject().isBlank()) {
            throw new Undeliverable("Mẫu " + template.code() + " không có tiêu đề (subject)");
        }
        String linkUrl = linkUrlOf(row);
        if (linkUrl != null && length(linkUrl) > Notification.MAX_LINK_URL_LENGTH) {
            throw new Undeliverable("link_url dài quá " + Notification.MAX_LINK_URL_LENGTH + " ký tự");
        }
        return Notification.deliver(accountId, type, title(rendered.subject()), rendered.body(), linkUrl);
    }

    /** {@code APPOINTMENT_REMINDER_APP} → {@code APPOINTMENT_REMINDER}; mã không có hậu tố giữ nguyên. */
    static String typeOf(String templateCode) {
        return templateCode.endsWith(APP_SUFFIX) && templateCode.length() > APP_SUFFIX.length()
                ? templateCode.substring(0, templateCode.length() - APP_SUFFIX.length())
                : templateCode;
    }

    /** Cắt theo ký tự Unicode (code point) như {@code VARCHAR(n)} của Postgres, không cắt đôi cặp surrogate. */
    static String title(String subject) {
        if (length(subject) <= Notification.MAX_TITLE_LENGTH) {
            return subject;
        }
        int keep = Notification.MAX_TITLE_LENGTH - length(ELLIPSIS);
        return subject.substring(0, subject.offsetByCodePoints(0, keep)) + ELLIPSIS;
    }

    /** Độ dài theo code point — đơn vị Postgres dùng cho {@code VARCHAR(n)}. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** {@code linkUrl} của request nằm trong payload ({@code NotificationService.LINK_URL_KEY}). */
    private static String linkUrlOf(NotificationOutbox row) {
        Object value = row.getPayload().get(NotificationService.LINK_URL_KEY);
        if (value == null) {
            return null;
        }
        String link = String.valueOf(value);
        return link.isBlank() ? null : link;
    }

    /** Dòng không bao giờ giao được dù thử lại; chỉ dùng trong lớp này. */
    private static final class Undeliverable extends Exception {
        Undeliverable(String message) {
            super(message, null, false, false);
        }
    }
}
