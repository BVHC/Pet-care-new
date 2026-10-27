package com.petcare.module.visit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;

/**
 * Chọn kênh nhắc theo BR-TB-02 cho ST04 (docs/adr/0082). Bản sao hàm thuần
 * {@code appointment/service/AppointmentNoticeTargets.requests} (lớp package-private của appointment): visit không import
 * được, và chép ~10 dòng rẻ hơn mở thêm một {@code api} chung. Hai bản phải giữ cùng quy tắc (06 §8 Q4).
 * <ul>
 * <li>tài khoản đã xác thực → {@code IN_APP} (kèm link) + {@code EMAIL} tới email <b>của tài khoản</b> (hồ sơ online có
 * {@code customers.email = null});</li>
 * <li>tài khoản {@code PENDING} → coi như không có tài khoản (FK chặn ST02 xóa; không gửi tới email chưa xác thực);</li>
 * <li>không tài khoản, hồ sơ có email → {@code EMAIL};</li>
 * <li>không kênh nào → rỗng: bên gọi sinh Care Task gọi điện nếu hồ sơ có SĐT.</li>
 * </ul>
 */
final class ReminderChannels {

    private ReminderChannels() {
    }

    static List<NotificationRequest> requests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload, String emailCode, String appCode, String linkUrl) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(appCode, Channel.IN_APP, account.accountId(), null, payload, linkUrl));
            requests.add(new NotificationRequest(emailCode, Channel.EMAIL, null, account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(emailCode, Channel.EMAIL, null, contact.email(), payload, null));
        }
        return requests;
    }
}
