package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;

/** {@link ReminderChannels} (BR-TB-02, docs/adr/0082): cùng quy tắc với {@code AppointmentNoticeTargets.requests}. */
class ReminderChannelsTest {

    private static final Map<String, Object> PAYLOAD = Map.of("ten_thu_cung", "Mi");

    private static CustomerContact contact(String email, Long accountId) {
        return new CustomerContact(7L, "Nguyễn Văn A", "0901234567", email, accountId, false);
    }

    private static List<NotificationRequest> requests(CustomerContact contact, AccountContact account) {
        return ReminderChannels.requests(contact, account, PAYLOAD, "X_REMINDER", "X_REMINDER_APP", "/booking?petId=9");
    }

    @Test
    void verifiedAccountGetsInAppWithLinkAndEmailToTheAccountAddress() {
        List<NotificationRequest> requests = requests(contact(null, 5L), new AccountContact(5L, "a@x.vn", false));

        assertThat(requests).extracting(NotificationRequest::templateCode, NotificationRequest::channel,
                NotificationRequest::recipientAccountId, NotificationRequest::recipientEmail,
                NotificationRequest::linkUrl).containsExactly(
                        tuple("X_REMINDER_APP", Channel.IN_APP, 5L, null, "/booking?petId=9"),
                        tuple("X_REMINDER", Channel.EMAIL, null, "a@x.vn", null));
    }

    @Test
    void accountEmailWinsOverTheProfileEmail() {
        List<NotificationRequest> requests = requests(contact("old@x.vn", 5L), new AccountContact(5L, "a@x.vn", false));

        assertThat(requests).extracting(NotificationRequest::recipientEmail).containsExactly(null, "a@x.vn");
    }

    @Test
    void pendingAccountIsTreatedAsNoAccountAndFallsBackToTheProfileEmail() {
        List<NotificationRequest> requests = requests(contact("p@x.vn", 5L), new AccountContact(5L, "a@x.vn", true));

        assertThat(requests).extracting(NotificationRequest::templateCode, NotificationRequest::channel,
                NotificationRequest::recipientEmail).containsExactly(tuple("X_REMINDER", Channel.EMAIL, "p@x.vn"));
    }

    @Test
    void counterProfileWithEmailGetsEmailOnly() {
        List<NotificationRequest> requests = requests(contact("c@x.vn", null), null);

        assertThat(requests).extracting(NotificationRequest::channel, NotificationRequest::recipientEmail,
                NotificationRequest::linkUrl).containsExactly(tuple(Channel.EMAIL, "c@x.vn", null));
    }

    @Test
    void noAccountAndBlankOrNullEmailGivesNoChannel() {
        assertThat(requests(contact(null, null), null)).isEmpty();
        assertThat(requests(contact("  ", null), null)).isEmpty();
        assertThat(requests(contact(null, 5L), new AccountContact(5L, "a@x.vn", true))).isEmpty();
    }
}
