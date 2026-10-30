package com.petcare.module.boarding.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;

/**
 * Kênh và người nhận của khách theo BR-TB-02 cho thông báo lưu trú: tài khoản không {@code PENDING} → IN_APP + EMAIL
 * tới email tài khoản; hồ sơ tại quầy có email → EMAIL; không kênh nào → rỗng (bên gọi log, lễ tân gọi theo màn hình).
 */
final class BoardingRecipients {

    private BoardingRecipients() {
    }

    /** {@code account} null khi hồ sơ không gắn tài khoản; {@code appLink} chỉ gắn cho bản IN_APP. */
    static List<NotificationRequest> customerRequests(String emailTemplate, String appTemplate, CustomerContact contact,
            AccountContact account, Map<String, Object> payload, String appLink) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(appTemplate, Channel.IN_APP, account.accountId(), null, payload,
                    appLink));
            requests.add(new NotificationRequest(emailTemplate, Channel.EMAIL, null, account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(emailTemplate, Channel.EMAIL, null, contact.email(), payload, null));
        }
        return requests;
    }
}
