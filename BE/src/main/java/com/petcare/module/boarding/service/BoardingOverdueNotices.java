package com.petcare.module.boarding.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingCheckIn;
import com.petcare.module.boarding.repository.BoardingCheckInRepository;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
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
 * Thông báo của ST15 (BR-LT-10, docs/adr/0086): khách mỗi ngày (BR-TB-02), BRANCH_MANAGER khi quá hạn từ 7 ngày [CFG],
 * lễ tân khi đặt chỗ quá hạn làm thiếu chỗ. Chỉ ghi outbox trong transaction của bên gọi; mọi biến bắt buộc khác
 * {@code null} (rollback thay vì để ST20 đánh dấu {@code FAILED}).
 */
@Component
class BoardingOverdueNotices {

    /** Màn hình lưu trú của khách / nhân viên. */
    static final String CUSTOMER_LINK = "/boarding-bookings/";
    static final String STAFF_LINK = "/admin/boarding";
    /** Hiển thị khi đặt chỗ chưa có SĐT khẩn (dữ liệu cũ); biến {@code sdt_khan} bắt buộc. */
    static final String NO_PHONE = "chưa có";

    private static final Logger log = LoggerFactory.getLogger(BoardingOverdueNotices.class);

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;
    private final StaffDirectoryApi staff;
    private final BoardingCheckInRepository checkIns;

    BoardingOverdueNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            BranchQueryApi branches, AccountContactApi accounts, StaffDirectoryApi staff,
            BoardingCheckInRepository checkIns) {
        this.notifications = notifications;
        this.customers = customers;
        this.pets = pets;
        this.branches = branches;
        this.accounts = accounts;
        this.staff = staff;
        this.checkIns = checkIns;
    }

    /** Thông báo hằng ngày cho khách; hồ sơ không kênh nào thì chỉ log. */
    void customerOverdue(BoardingBooking booking, int daysOverdue) {
        CustomerContact contact = customers.findContact(booking.getCustomerId()).orElseThrow(
                () -> new IllegalStateException("Đặt chỗ #" + booking.getId() + " trỏ tới hồ sơ khách không có"));
        BranchSummary branch = branch(booking);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ten_thu_cung", petName(booking));
        payload.put("chi_nhanh", branch.name());
        payload.put("so_dien_thoai_chi_nhanh", branch.phone());
        payload.put("ma_dat_cho", booking.getCode());
        payload.put("ngay_tra", BoardingClinicCancellationNotices.DATE_FORMAT.format(booking.getCheckOutDate()));
        payload.put("so_ngay_qua_han", String.valueOf(daysOverdue));
        requireVars(booking, payload, "ten_thu_cung", "chi_nhanh", "so_dien_thoai_chi_nhanh", "ma_dat_cho",
                "ngay_tra", "so_ngay_qua_han");
        AccountContact account = contact.accountId() == null ? null
                : accounts.findContacts(List.of(contact.accountId())).stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("Hồ sơ khách #" + contact.customerId()
                                + " trỏ tới tài khoản không có"));
        List<NotificationRequest> requests = BoardingRecipients.customerRequests(
                NotificationTemplateCode.BOARDING_OVERDUE, NotificationTemplateCode.BOARDING_OVERDUE_APP, contact,
                account, payload, CUSTOMER_LINK + booking.getId());
        if (requests.isEmpty()) {
            log.info("BOARDING_OVERDUE_NO_CHANNEL bookingId={} customerId={}", booking.getId(), contact.customerId());
        }
        requests.forEach(notifications::enqueue);
    }

    /** BRANCH_MANAGER của chi nhánh; không còn ai hoạt động thì mọi SUPER_MANAGER (như ADR-0035). */
    void managerOverdue(BoardingBooking booking, int daysOverdue) {
        List<Long> recipients = staff.findActiveStaffIds(booking.getBranchId(), Role.BRANCH_MANAGER);
        if (recipients.isEmpty()) {
            recipients = staff.findActiveSuperManagerIds();
            log.warn("BOARDING_OVERDUE_FALLBACK_SUPER_MANAGER bookingId={} recipients={}", booking.getId(),
                    recipients.size());
        }
        String phone = checkIns.findById(booking.getId()).map(BoardingCheckIn::getEmergencyPhone).orElse(NO_PHONE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_thu_cung", petName(booking));
        payload.put("ma_dat_cho", booking.getCode());
        payload.put("ngay_tra", BoardingClinicCancellationNotices.DATE_FORMAT.format(booking.getCheckOutDate()));
        payload.put("so_ngay_qua_han", String.valueOf(daysOverdue));
        payload.put("sdt_khan", phone);
        requireVars(booking, payload, "ten_thu_cung", "ma_dat_cho", "ngay_tra", "so_ngay_qua_han", "sdt_khan");
        for (Long accountId : recipients) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.BOARDING_OVERDUE_MANAGER_APP,
                    Channel.IN_APP, accountId, null, payload, STAFF_LINK));
        }
    }

    /** Mỗi lễ tân hoạt động của chi nhánh một bản; không có lễ tân thì log, ST15 vẫn chạy. */
    void capacityWarning(BoardingBooking overdue, List<BoardingBooking> affected, String kennelTypeName) {
        List<Long> receptionists = staff.findActiveStaffIds(overdue.getBranchId(), Role.RECEPTIONIST);
        if (receptionists.isEmpty()) {
            log.warn("BOARDING_CAPACITY_WARNING_NO_RECEPTIONIST bookingId={} branchId={}", overdue.getId(),
                    overdue.getBranchId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loai_chuong", kennelTypeName);
        payload.put("ma_dat_cho_qua_han", overdue.getCode());
        payload.put("danh_sach_dat_cho", String.join(", ", affected.stream().map(BoardingBooking::getCode).toList()));
        requireVars(overdue, payload, "loai_chuong", "ma_dat_cho_qua_han", "danh_sach_dat_cho");
        for (Long accountId : receptionists) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.BOARDING_CAPACITY_WARNING_APP,
                    Channel.IN_APP, accountId, null, payload, STAFF_LINK));
        }
    }

    private BranchSummary branch(BoardingBooking booking) {
        return branches.findBranch(booking.getBranchId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới chi nhánh không có: " + booking.getBranchId()));
    }

    private String petName(BoardingBooking booking) {
        return pets.findPet(booking.getPetId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới thú không có: " + booking.getPetId())).name();
    }

    private static void requireVars(BoardingBooking booking, Map<String, Object> payload, String... names) {
        for (String name : names) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho thông báo quá hạn đón của đặt chỗ "
                        + booking.getId());
            }
        }
    }
}
