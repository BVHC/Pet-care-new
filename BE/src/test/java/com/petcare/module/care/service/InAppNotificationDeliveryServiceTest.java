package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.Notification;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Một lần giao của ST20 luồng IN_APP (docs/adr/0014): dòng outbox → một dòng {@code notifications} rồi {@code SENT};
 * mọi giới hạn cột kiểm trước INSERT; dòng không giao được → {@code FAILED}; lỗi DB đi thẳng ra để rollback.
 */
class InAppNotificationDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final String CODE = "CARE_LOG_ABNORMAL_APP";
    private static final TemplateView TEMPLATE = new TemplateView(CODE, "IN_APP", "Bé {ten_thu} có dấu hiệu bất thường",
            "Nhật ký ngày {ngay}: {ghi_chu}", List.of("ten_thu", "ngay", "ghi_chu"), List.of("ten_thu"));

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationTemplateQueryApi templates = mock(NotificationTemplateQueryApi.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InAppNotificationDeliveryService service = new InAppNotificationDeliveryService(outbox,
            notifications, templates, new NotificationTemplateRenderer(), meters);

    @BeforeEach
    void setUp() {
        when(templates.findTemplate(CODE)).thenReturn(Optional.of(TEMPLATE));
    }

    private static NotificationOutbox row(String templateCode, Long accountId, Map<String, Object> payload) {
        NotificationOutbox row = NotificationOutbox.pending(Channel.IN_APP, templateCode, null, accountId, payload,
                NOW.minusSeconds(3));
        ReflectionTestUtils.setField(row, "id", 42L);
        return row;
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ten_thu", "Mít");
        payload.put("ngay", "07/10/2026");
        payload.put("ghi_chu", "bỏ ăn");
        payload.put("link_url", "/me/boardings/7");
        payload.put("ma_otp", "123456");
        return payload;
    }

    private NotificationOutbox due(NotificationOutbox row) {
        when(outbox.lockNextDueInApp(NOW)).thenReturn(Optional.of(row));
        return row;
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        return captor.getValue();
    }

    private void assertFailedWithoutNotification(NotificationOutbox row, String errorPart) {
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).contains(errorPart);
        verify(notifications, never()).save(any());
    }

    // ---------------------------------------------------------------- giao được

    @Test
    void noDueRowMeansNone() {
        when(outbox.lockNextDueInApp(NOW)).thenReturn(Optional.empty());

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.NONE);

        verify(notifications, never()).save(any());
    }

    @Test
    void deliversOneNotificationThenMarksSent() {
        NotificationOutbox row = due(row(CODE, 7L, payload()));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.SENT);

        Notification notification = saved();
        assertThat(notification.getAccountId()).isEqualTo(7L);
        assertThat(notification.getType()).isEqualTo("CARE_LOG_ABNORMAL");
        assertThat(notification.getTitle()).isEqualTo("Bé Mít có dấu hiệu bất thường");
        assertThat(notification.getBody()).isEqualTo("Nhật ký ngày 07/10/2026: bỏ ăn");
        assertThat(notification.getLinkUrl()).isEqualTo("/me/boardings/7");
        assertThat(notification.getReadAt()).isNull();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getPayload()).doesNotContainKey("ma_otp").containsKey("ten_thu");
        assertThat(meters.get("notification.outbox.queue.latency").tag("lane", "IN_APP").timer().count())
                .isEqualTo(1);
    }

    /** INSERT trước, {@code SENT} sau, cùng transaction: lỗi INSERT thì không có dòng {@code SENT} mà thiếu thông báo. */
    @Test
    void insertHappensBeforeMarkingSent() {
        NotificationOutbox row = due(row(CODE, 7L, payload()));
        InOrder order = inOrder(outbox, notifications);

        service.deliverNext(NOW);

        order.verify(outbox).lockNextDueInApp(NOW);
        order.verify(notifications).save(any());
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @ParameterizedTest
    @CsvSource({
            "CARE_LOG_ABNORMAL_APP, CARE_LOG_ABNORMAL",
            "LAST_BRANCH_MANAGER_LOCKED_APP, LAST_BRANCH_MANAGER_LOCKED",
            "IT_APP_NOTICE, IT_APP_NOTICE",
            "_APP, _APP"})
    void typeIsTemplateCodeWithoutAppSuffix(String code, String type) {
        assertThat(InAppNotificationDeliveryService.typeOf(code)).isEqualTo(type);
    }

    @Test
    void missingOrBlankLinkUrlIsNull() {
        Map<String, Object> payload = payload();
        payload.put("link_url", " ");
        due(row(CODE, 7L, payload));

        service.deliverNext(NOW);

        assertThat(saved().getLinkUrl()).isNull();
    }

    // ---------------------------------------------------------------- giới hạn cột (V1 notifications)

    @Test
    void titleAtLimitIsKept() {
        String title = "a".repeat(Notification.MAX_TITLE_LENGTH);
        assertThat(InAppNotificationDeliveryService.title(title)).isEqualTo(title);
    }

    @Test
    void titleOverLimitIsCutWithEllipsis() {
        String cut = InAppNotificationDeliveryService.title("a".repeat(Notification.MAX_TITLE_LENGTH + 1));

        assertThat(cut).hasSize(Notification.MAX_TITLE_LENGTH).endsWith("…");
    }

    /** VARCHAR(n) đếm ký tự Unicode: emoji (2 đơn vị UTF-16) tính là 1, không bị cắt đôi. */
    @Test
    void titleIsMeasuredAndCutByCodePoint() {
        String emoji = "🐶";
        String atLimit = emoji.repeat(Notification.MAX_TITLE_LENGTH);
        assertThat(InAppNotificationDeliveryService.title(atLimit)).isEqualTo(atLimit);

        String cut = InAppNotificationDeliveryService.title(emoji.repeat(Notification.MAX_TITLE_LENGTH + 1));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(Notification.MAX_TITLE_LENGTH);
        assertThat(cut).isEqualTo(emoji.repeat(Notification.MAX_TITLE_LENGTH - 1) + "…");
    }

    @Test
    void longRenderedTitleIsDeliveredCut() {
        Map<String, Object> payload = payload();
        payload.put("ten_thu", "x".repeat(300));
        due(row(CODE, 7L, payload));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.SENT);

        assertThat(saved().getTitle()).hasSize(Notification.MAX_TITLE_LENGTH).endsWith("…");
    }

    @Test
    void linkUrlAtLimitIsDelivered() {
        Map<String, Object> payload = payload();
        payload.put("link_url", "/" + "a".repeat(Notification.MAX_LINK_URL_LENGTH - 1));
        due(row(CODE, 7L, payload));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.SENT);
    }

    @Test
    void linkUrlOverLimitFails() {
        Map<String, Object> payload = payload();
        payload.put("link_url", "/" + "a".repeat(Notification.MAX_LINK_URL_LENGTH));
        NotificationOutbox row = due(row(CODE, 7L, payload));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "link_url");
    }

    @Test
    void typeAtLimitIsDeliveredOverLimitFails() {
        String atLimit = "T".repeat(Notification.MAX_TYPE_LENGTH) + "_APP";
        String overLimit = "T".repeat(Notification.MAX_TYPE_LENGTH + 1) + "_APP";
        for (String code : List.of(atLimit, overLimit)) {
            when(templates.findTemplate(code)).thenReturn(Optional.of(new TemplateView(code, "IN_APP", "Tiêu đề",
                    "Nội dung", List.of(), List.of())));
        }

        due(row(atLimit, 7L, Map.of()));
        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.SENT);

        NotificationOutbox over = due(row(overLimit, 7L, Map.of()));
        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);
        assertThat(over.getLastError()).contains(String.valueOf(Notification.MAX_TYPE_LENGTH));
    }

    // ---------------------------------------------------------------- không giao được → FAILED

    @Test
    void missingRecipientAccountFails() {
        NotificationOutbox row = due(row(CODE, null, payload()));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "recipient_account_id");
        assertThat(row.getPayload()).doesNotContainKey("ma_otp");
    }

    @Test
    void unknownTemplateFails() {
        NotificationOutbox row = due(row("NO_SUCH_APP", 7L, payload()));
        when(templates.findTemplate("NO_SUCH_APP")).thenReturn(Optional.empty());

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "NO_SUCH_APP");
    }

    @Test
    void emailTemplateFails() {
        NotificationOutbox row = due(row("OTP_REGISTER", 7L, payload()));
        when(templates.findTemplate("OTP_REGISTER")).thenReturn(Optional.of(new TemplateView("OTP_REGISTER", "EMAIL",
                "Mã", "{ma_otp}", List.of("ma_otp"), List.of("ma_otp"))));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "EMAIL");
    }

    @Test
    void missingRequiredVariableFails() {
        Map<String, Object> payload = payload();
        payload.remove("ten_thu");
        NotificationOutbox row = due(row(CODE, 7L, payload));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "ten_thu");
    }

    @Test
    void templateWithoutSubjectFails() {
        NotificationOutbox row = due(row(CODE, 7L, payload()));
        when(templates.findTemplate(CODE)).thenReturn(Optional.of(new TemplateView(CODE, "IN_APP", null, "Nội dung",
                List.of(), List.of())));

        assertThat(service.deliverNext(NOW)).isEqualTo(DispatchResult.FAILED);

        assertFailedWithoutNotification(row, "subject");
    }

    // ---------------------------------------------------------------- lỗi DB

    /** Lỗi DB không được "nuốt" thành FAILED: thoát ra để transaction rollback, dòng giữ PENDING cho lượt sau. */
    @Test
    void databaseErrorPropagatesWithoutChangingRow() {
        NotificationOutbox row = due(row(CODE, 7L, payload()));
        when(notifications.save(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.deliverNext(NOW)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    // ---------------------------------------------------------------- tồn đọng

    @Test
    void backlogCountsDueRowsAndOldestDueTime() {
        NotificationOutbox oldest = row(CODE, 7L, payload());
        when(outbox.countDueInApp(NOW)).thenReturn(4L);
        when(outbox.findOldestDueInApp(NOW)).thenReturn(Optional.of(oldest));

        assertThat(service.backlog(NOW)).isEqualTo(new LaneBacklog(4, NOW.minusSeconds(3)));
    }

    @Test
    void emptyBacklogSkipsOldestQuery() {
        when(outbox.countDueInApp(NOW)).thenReturn(0L);

        assertThat(service.backlog(NOW)).isEqualTo(new LaneBacklog(0, null));

        verify(outbox, never()).findOldestDueInApp(any());
    }
}
