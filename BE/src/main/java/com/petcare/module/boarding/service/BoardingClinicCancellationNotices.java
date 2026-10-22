package com.petcare.module.boarding.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Thông báo khách khi phòng khám hủy đặt chỗ lưu trú (Đặt chỗ#5, docs/adr/0065; khuôn
 * {@code appointment.service.ClinicCancellationNotices}). Chỉ ghi outbox trong transaction của bên gọi. Người nhận theo
 * BR-TB-02: tài khoản đã xác thực → IN_APP + EMAIL tới email tài khoản; hồ sơ tại quầy có email → EMAIL; không kênh nào
 * → không gửi (lễ tân gọi theo danh sách, 06 §8). Chuyển chủ báo chủ lúc đặt ({@code customer_id} của đặt chỗ).
 * Tra theo lô ≤ {@value #BATCH}; thiếu / trùng id là dữ liệu hỏng → {@link IllegalStateException} (rollback).
 */
@Component
class BoardingClinicCancellationNotices {

    static final int BATCH = 100;
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Màn hình đặt chỗ lưu trú của khách. */
    static final String LINK_URL = "/boarding-bookings";
    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "chi_nhanh", "ngay_nhan", "ngay_tra",
            "ma_dat_cho", "ly_do");

    private static final Logger log = LoggerFactory.getLogger(BoardingClinicCancellationNotices.class);

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;

    BoardingClinicCancellationNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            BranchQueryApi branches, AccountContactApi accounts) {
        this.notifications = notifications;
        this.customers = customers;
        this.pets = pets;
        this.branches = branches;
        this.accounts = accounts;
    }

    /** Ghi outbox cho từng đặt chỗ đã hủy; danh sách rỗng thì không gọi module nào. */
    void enqueueFor(List<BoardingBooking> cancelled, BoardingClinicCause cause) {
        if (cancelled.isEmpty()) {
            return;
        }
        Map<Long, CustomerContact> contactById = fetch(cancelled.stream().map(BoardingBooking::getCustomerId)
                .distinct().toList(), customers::findContacts, CustomerContact::customerId, "findContacts");
        Map<Long, PetSummary> petById = fetch(cancelled.stream().map(BoardingBooking::getPetId).distinct().toList(),
                pets::findPets, PetSummary::petId, "findPets");
        Map<Long, BranchSummary> branchById = new HashMap<>();
        for (Long branchId : cancelled.stream().map(BoardingBooking::getBranchId).distinct().toList()) {
            branchById.put(branchId, branches.findBranch(branchId).orElseThrow(
                    () -> new IllegalStateException("Đặt chỗ trỏ tới chi nhánh không tồn tại: " + branchId)));
        }
        List<Long> accountIds = contactById.values().stream().map(CustomerContact::accountId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, AccountContact> accountById = fetch(accountIds, accounts::findContacts, AccountContact::accountId,
                "AccountContactApi.findContacts");

        for (BoardingBooking booking : cancelled) {
            CustomerContact contact = require(contactById, booking.getCustomerId(), "hồ sơ khách");
            AccountContact account = contact.accountId() == null ? null
                    : require(accountById, contact.accountId(), "tài khoản");
            Map<String, Object> payload = payload(contact, require(petById, booking.getPetId(), "thú cưng").name(),
                    require(branchById, booking.getBranchId(), "chi nhánh").name(), booking, cause);
            List<NotificationRequest> requests = requests(contact, account, payload);
            if (requests.isEmpty()) {
                log.info("BOARDING_CLINIC_CANCEL_NO_CHANNEL bookingId={} customerId={}", booking.getId(),
                        contact.customerId());
            }
            requests.forEach(notifications::enqueue);
        }
    }

    /** Biến của mẫu; mọi biến bắt buộc khác {@code null} (rollback thay vì để ST20 đánh dấu {@code FAILED}). */
    static Map<String, Object> payload(CustomerContact contact, String petName, String branchName,
            BoardingBooking booking, BoardingClinicCause cause) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ten_thu_cung", petName);
        payload.put("chi_nhanh", branchName);
        payload.put("ngay_nhan", DATE_FORMAT.format(booking.getCheckInDate()));
        payload.put("ngay_tra", DATE_FORMAT.format(booking.getCheckOutDate()));
        payload.put("ma_dat_cho", booking.getCode());
        payload.put("ly_do", cause.reason());
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho thông báo hủy đặt chỗ lưu trú "
                        + booking.getId());
            }
        }
        return payload;
    }

    /** Kênh và người nhận theo BR-TB-02; {@code account} null khi hồ sơ không gắn tài khoản. */
    static List<NotificationRequest> requests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.BOARDING_CANCELLED_BY_CLINIC_APP,
                    Channel.IN_APP, account.accountId(), null, payload, LINK_URL));
            requests.add(new NotificationRequest(NotificationTemplateCode.BOARDING_CANCELLED_BY_CLINIC, Channel.EMAIL,
                    null, account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.BOARDING_CANCELLED_BY_CLINIC, Channel.EMAIL,
                    null, contact.email(), payload, null));
        }
        return requests;
    }

    private static <T> Map<Long, T> fetch(List<Long> ids, Function<Collection<Long>, List<T>> call,
            Function<T, Long> idOf, String name) {
        Map<Long, T> byId = new HashMap<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            for (T row : call.apply(ids.subList(from, Math.min(ids.size(), from + BATCH)))) {
                if (byId.put(idOf.apply(row), row) != null) {
                    throw new IllegalStateException(name + " trả trùng id " + idOf.apply(row));
                }
            }
        }
        return byId;
    }

    private static <T> T require(Map<Long, T> byId, Long id, String what) {
        T value = byId.get(id);
        if (value == null) {
            throw new IllegalStateException("Đặt chỗ lưu trú trỏ tới " + what + " không tồn tại: " + id);
        }
        return value;
    }
}
