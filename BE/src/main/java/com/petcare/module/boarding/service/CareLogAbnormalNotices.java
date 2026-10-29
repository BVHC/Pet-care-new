package com.petcare.module.boarding.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.CareLog;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

/**
 * Thông báo ngay khi mục nhật ký đánh dấu bất thường (BR-LT-12, Đặt chỗ#8, docs/adr/0085). Chỉ ghi outbox trong
 * transaction ghi nhật ký: lỗi thì mục nhật ký cũng không được lưu. Khách theo BR-TB-02 (tài khoản không {@code PENDING}
 * → IN_APP + EMAIL tới email tài khoản; hồ sơ tại quầy có email → EMAIL; không kênh nào → log, lễ tân gọi theo
 * màn hình); mỗi lễ tân hoạt động của chi nhánh nhận một bản IN_APP.
 */
@Component
class CareLogAbnormalNotices {

    /** Màn hình lưu trú của khách / của nhân viên. */
    static final String CUSTOMER_LINK = "/boarding-bookings/";
    static final String STAFF_LINK = "/admin/boarding";

    private static final Logger log = LoggerFactory.getLogger(CareLogAbnormalNotices.class);

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;
    private final StaffDirectoryApi staff;

    CareLogAbnormalNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            BranchQueryApi branches, AccountContactApi accounts, StaffDirectoryApi staff) {
        this.notifications = notifications;
        this.customers = customers;
        this.pets = pets;
        this.branches = branches;
        this.accounts = accounts;
        this.staff = staff;
    }

    void enqueue(BoardingBooking booking, CareLog careLog) {
        CustomerContact contact = customers.findContact(booking.getCustomerId()).orElseThrow(
                () -> new IllegalStateException("Đặt chỗ #" + booking.getId() + " trỏ tới hồ sơ khách không có"));
        String petName = pets.findPet(booking.getPetId()).orElseThrow(
                () -> new IllegalStateException("Đặt chỗ #" + booking.getId() + " trỏ tới thú không có")).name();
        String branchName = branches.findBranch(booking.getBranchId()).orElseThrow(
                () -> new IllegalStateException("Đặt chỗ #" + booking.getId() + " trỏ tới chi nhánh không có")).name();
        Map<String, Object> payload = payload(contact.fullName(), petName, branchName, booking, careLog);

        AccountContact account = contact.accountId() == null ? null : accounts.findContacts(List.of(contact.accountId()))
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "Hồ sơ khách #" + contact.customerId() + " trỏ tới tài khoản không có"));
        List<NotificationRequest> requests = new ArrayList<>(customerRequests(contact, account, payload,
                booking.getId()));
        if (requests.isEmpty()) {
            log.info("CARE_LOG_ABNORMAL_NO_CHANNEL bookingId={} customerId={}", booking.getId(), contact.customerId());
        }
        List<Long> receptionists = staff.findActiveStaffIds(booking.getBranchId(), Role.RECEPTIONIST);
        if (receptionists.isEmpty()) {
            log.warn("CARE_LOG_ABNORMAL_NO_RECEPTIONIST bookingId={} branchId={}", booking.getId(),
                    booking.getBranchId());
        }
        for (Long receptionistId : receptionists) {
            requests.add(new NotificationRequest(NotificationTemplateCode.CARE_LOG_ABNORMAL_APP, Channel.IN_APP,
                    receptionistId, null, payload, STAFF_LINK));
        }
        requests.forEach(notifications::enqueue);
    }

    /** Biến của mẫu; mọi biến bắt buộc khác {@code null} (rollback thay vì để ST20 đánh dấu {@code FAILED}). */
    static Map<String, Object> payload(String customerName, String petName, String branchName,
            BoardingBooking booking, CareLog careLog) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", customerName);
        payload.put("ten_thu_cung", petName);
        payload.put("chi_nhanh", branchName);
        payload.put("ma_dat_cho", booking.getCode());
        payload.put("ngay_ghi", BoardingClinicCancellationNotices.DATE_FORMAT.format(careLog.getLogDate()));
        for (String name : List.of("ten_thu_cung", "chi_nhanh", "ma_dat_cho", "ngay_ghi")) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho thông báo nhật ký bất thường "
                        + booking.getId());
            }
        }
        return payload;
    }

    /** Kênh và người nhận của khách theo BR-TB-02; {@code account} null khi hồ sơ không gắn tài khoản. */
    static List<NotificationRequest> customerRequests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload, Long bookingId) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.CARE_LOG_ABNORMAL_APP, Channel.IN_APP,
                    account.accountId(), null, payload, CUSTOMER_LINK + bookingId));
            requests.add(new NotificationRequest(NotificationTemplateCode.CARE_LOG_ABNORMAL, Channel.EMAIL, null,
                    account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.CARE_LOG_ABNORMAL, Channel.EMAIL, null,
                    contact.email(), payload, null));
        }
        return requests;
    }
}
