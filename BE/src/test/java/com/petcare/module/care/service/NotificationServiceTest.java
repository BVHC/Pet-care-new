package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.NotificationOutboxRepository;
import com.petcare.platform.config.TimeConfig;

/** NotificationApi.enqueue: chỉ ghi outbox (06 §1, convention 07 §7.2); request thiếu dữ liệu là lỗi lập trình. */
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final NotificationService service = new NotificationService(outbox, Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @Test
    void writesPendingRowDueNow() {
        service.enqueue(new NotificationRequest("OTP_REGISTER", Channel.EMAIL, null, "a@petcare.test",
                Map.of("ma_otp", "123456"), null));

        NotificationOutbox row = saved();
        assertThat(row.getTemplateCode()).isEqualTo("OTP_REGISTER");
        assertThat(row.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(row.getRecipientEmail()).isEqualTo("a@petcare.test");
        assertThat(row.getRecipientAccountId()).isNull();
        assertThat(row.getPayload()).containsExactlyEntriesOf(Map.of("ma_otp", "123456"));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void accountRecipientAndLinkUrlKeptInRow() {
        service.enqueue(new NotificationRequest("VACCINE_REMINDER_APP", Channel.IN_APP, 7L, null, Map.of(),
                "/booking?pet=3"));

        NotificationOutbox row = saved();
        assertThat(row.getRecipientAccountId()).isEqualTo(7L);
        assertThat(row.getRecipientEmail()).isNull();
        assertThat(row.getPayload()).containsEntry(NotificationService.LINK_URL_KEY, "/booking?pet=3");
    }

    @Test
    void missingRecipientIsProgrammingError() {
        assertInvalid(new NotificationRequest("OTP_REGISTER", Channel.EMAIL, null, " ", Map.of(), null));
    }

    /** EMAIL cần địa chỉ chốt lúc sự kiện; worker không tra email theo tài khoản (docs/adr/0012). */
    @Test
    void emailChannelRequiresRecipientEmailEvenWithAccount() {
        assertInvalid(new NotificationRequest("PASSWORD_CHANGED", Channel.EMAIL, 7L, null, Map.of(), null));
    }

    /** IN_APP ghi vào {@code notifications.account_id NN}: chỉ có email là không đủ. */
    @Test
    void inAppChannelRequiresRecipientAccount() {
        assertInvalid(new NotificationRequest("VACCINE_REMINDER_APP", Channel.IN_APP, null, "a@petcare.test",
                Map.of(), null));
    }

    @Test
    void emailWithAccountKeepsBoth() {
        service.enqueue(new NotificationRequest("PASSWORD_CHANGED", Channel.EMAIL, 7L, "a@petcare.test", Map.of(),
                null));

        NotificationOutbox row = saved();
        assertThat(row.getRecipientEmail()).isEqualTo("a@petcare.test");
        assertThat(row.getRecipientAccountId()).isEqualTo(7L);
    }

    @Test
    void missingTemplateChannelOrPayloadIsProgrammingError() {
        assertInvalid(new NotificationRequest(" ", Channel.EMAIL, null, "a@petcare.test", Map.of(), null));
        assertInvalid(new NotificationRequest("OTP_REGISTER", null, null, "a@petcare.test", Map.of(), null));
        assertInvalid(new NotificationRequest("OTP_REGISTER", Channel.EMAIL, null, "a@petcare.test", null, null));
        assertInvalid(null);
    }

    private void assertInvalid(NotificationRequest request) {
        assertThatThrownBy(() -> service.enqueue(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox);
    }

    private NotificationOutbox saved() {
        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }
}
