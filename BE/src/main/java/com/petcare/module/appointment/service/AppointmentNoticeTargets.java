package com.petcare.module.appointment.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;

/**
 * Phần dùng chung của các thông báo gửi khách về một lịch hẹn (hủy do phòng khám — docs/adr/0052; nhắc lịch ST03 —
 * docs/adr/0056): tra tên theo lô và chọn người nhận theo BR-TB-02. Không phải bean: mỗi lớp thông báo tự tạo với các
 * {@code *QueryApi} được tiêm, nên không đổi cách dựng của chúng. Chỉ đọc, không ghi gì.
 * <p>
 * Tra theo lô ≤ {@value #BATCH} id mỗi lần gọi (khuôn {@code ScheduleImpactFinder}), ghép theo id; danh sách rỗng thì không
 * gọi module đó. Id thiếu hoặc trùng là dữ liệu hỏng → {@link IllegalStateException} (rollback cả use case của bên gọi).
 */
final class AppointmentNoticeTargets {

    static final int BATCH = 100;

    /** Các khóa của một lịch hẹn cần để tra tên; {@code appointmentId} chỉ để log. */
    record Subject(Long appointmentId, Long customerId, Long petId, Long serviceId, Long branchId) {}

    /**
     * Mọi thứ cần để soạn thông báo cho một lịch; {@code account} null khi hồ sơ không gắn tài khoản. Tài khoản
     * {@code PENDING} vẫn có mặt ở đây (cờ {@code pending}) — {@link #requests} mới là nơi loại nó.
     */
    record Target(Subject subject, CustomerContact contact, AccountContact account, PetSummary pet,
                  ServiceInfo service, BranchSummary branch) {}

    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;

    AppointmentNoticeTargets(CustomerQueryApi customers, PetQueryApi pets, CatalogQueryApi catalog,
            BranchQueryApi branches, AccountContactApi accounts) {
        this.customers = customers;
        this.pets = pets;
        this.catalog = catalog;
        this.branches = branches;
        this.accounts = accounts;
    }

    /** Một {@link Target} cho mỗi {@code subject}, cùng thứ tự; rỗng thì không gọi module nào. */
    List<Target> resolve(List<Subject> subjects) {
        if (subjects.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerContact> contactById = fetch(ids(subjects, Subject::customerId), customers::findContacts,
                CustomerContact::customerId, "findContacts");
        Map<Long, PetSummary> petById = fetch(ids(subjects, Subject::petId), pets::findPets, PetSummary::petId,
                "findPets");
        Map<Long, ServiceInfo> serviceById = fetch(ids(subjects, Subject::serviceId), catalog::findServices,
                ServiceInfo::serviceId, "findServices");
        Map<Long, BranchSummary> branchById = new HashMap<>();
        for (Long branchId : ids(subjects, Subject::branchId)) {
            branchById.put(branchId, branches.findBranch(branchId).orElseThrow(
                    () -> new IllegalStateException("Lịch hẹn trỏ tới chi nhánh không tồn tại: " + branchId)));
        }
        List<Long> accountIds = contactById.values().stream().map(CustomerContact::accountId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, AccountContact> accountById = fetch(accountIds, accounts::findContacts, AccountContact::accountId,
                "AccountContactApi.findContacts");

        List<Target> targets = new ArrayList<>(subjects.size());
        for (Subject subject : subjects) {
            CustomerContact contact = require(contactById, subject.customerId(), "hồ sơ khách");
            targets.add(new Target(subject, contact,
                    contact.accountId() == null ? null : require(accountById, contact.accountId(), "tài khoản"),
                    require(petById, subject.petId(), "thú cưng"),
                    require(serviceById, subject.serviceId(), "dịch vụ"),
                    require(branchById, subject.branchId(), "chi nhánh")));
        }
        return targets;
    }

    /**
     * Kênh và người nhận theo BR-TB-02 (06 §8 Q4; docs/adr/0052 Q2):
     * <ul>
     * <li>tài khoản đã xác thực → {@code IN_APP} ({@code appCode}) + {@code EMAIL} ({@code emailCode}) tới email <b>của
     * tài khoản</b> (hồ sơ online có {@code customers.email = null});</li>
     * <li>tài khoản {@code PENDING} → coi như không có tài khoản (FK chặn ST02; không gửi tới email chưa xác thực);</li>
     * <li>không tài khoản, hồ sơ có email → {@code EMAIL};</li>
     * <li>không kênh nào → rỗng (không Care Task: BR-TB-05 không có loại cho thông báo lịch hẹn).</li>
     * </ul>
     */
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

    private static List<Long> ids(List<Subject> subjects, Function<Subject, Long> id) {
        Set<Long> ids = new LinkedHashSet<>();
        subjects.forEach(s -> ids.add(id.apply(s)));
        return List.copyOf(ids);
    }

    /** Chia lô ≤ {@value #BATCH}, ghép theo id; trùng id là vi phạm nghĩa vụ của bên cung cấp. */
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
            throw new IllegalStateException("Lịch hẹn trỏ tới " + what + " không tồn tại: " + id);
        }
        return value;
    }
}
