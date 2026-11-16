package com.petcare.module.shop.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.shop.entity.OnlineOrder;

/**
 * Thông báo cho khách của xử lý và giao đơn online (BR-BH-11, 08 §B #6 và #8; docs/adr/0146). Chỉ ghi outbox trong
 * transaction của bên gọi (rollback thì không gửi gì); mẫu V40 có biến bắt buộc mà {@code enqueue} không kiểm nên mỗi
 * hàm tự điền đủ. Kênh theo BR-TB-02 như đơn vừa đặt: tài khoản không {@code PENDING} → IN_APP + EMAIL tới email tài
 * khoản; ngược lại EMAIL tới email hồ sơ nếu có; không kênh nào thì thôi.
 */
@Component
class OnlineOrderNotices {

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final AccountContactApi accounts;
    private final BranchQueryApi branches;

    OnlineOrderNotices(NotificationApi notifications, CustomerQueryApi customers, AccountContactApi accounts,
            BranchQueryApi branches) {
        this.notifications = notifications;
        this.customers = customers;
        this.accounts = accounts;
        this.branches = branches;
    }

    /** ONLINE_ORDER_SHIPPED (#6): {@code ma_don}, {@code don_vi_van_chuyen}, {@code ma_van_don}. */
    void shipped(OnlineOrder order) {
        CustomerContact contact = contact(order);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ma_don", order.getCode());
        payload.put("don_vi_van_chuyen", order.getCarrier());
        payload.put("ma_van_don", order.getTrackingCode());
        send(contact, NotificationTemplateCode.ONLINE_ORDER_SHIPPED, NotificationTemplateCode.ONLINE_ORDER_SHIPPED_APP,
                payload);
    }

    /** ONLINE_ORDER_DELIVERY_FAILED (#8): {@code ma_don}, {@code ly_do}, {@code chi_nhanh}, {@code so_dien_thoai_chi_nhanh}. */
    void deliveryFailed(OnlineOrder order, String reason) {
        CustomerContact contact = contact(order);
        BranchSummary branch = branches.findBranch(order.getBranchId()).orElseThrow(() -> new IllegalStateException(
                "Chi nhánh #" + order.getBranchId() + " của đơn online " + order.getCode() + " không tồn tại"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ma_don", order.getCode());
        payload.put("ly_do", reason);
        payload.put("chi_nhanh", branch.name());
        payload.put("so_dien_thoai_chi_nhanh", branch.phone());
        send(contact, NotificationTemplateCode.ONLINE_ORDER_DELIVERY_FAILED,
                NotificationTemplateCode.ONLINE_ORDER_DELIVERY_FAILED_APP, payload);
    }

    private CustomerContact contact(OnlineOrder order) {
        return customers.findContact(order.getCustomerId()).orElseThrow(() -> new IllegalStateException(
                "Đơn online " + order.getCode() + " trỏ tới hồ sơ khách không có: " + order.getCustomerId()));
    }

    private void send(CustomerContact contact, String emailTemplate, String appTemplate, Map<String, Object> payload) {
        payload.forEach((name, value) -> {
            if (value == null) {
                throw new IllegalStateException("Thiếu biến " + name + " cho thông báo " + emailTemplate);
            }
        });
        AccountContact account = contact.accountId() == null ? null
                : accounts.findContacts(List.of(contact.accountId())).stream().findFirst().orElse(null);
        if (account != null && !account.pending()) {
            notifications.enqueue(new NotificationRequest(appTemplate, Channel.IN_APP, account.accountId(), null,
                    payload, null));
            notifications.enqueue(new NotificationRequest(emailTemplate, Channel.EMAIL, null, account.email(), payload,
                    null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            notifications.enqueue(new NotificationRequest(emailTemplate, Channel.EMAIL, null, contact.email(), payload,
                    null));
        }
    }
}
