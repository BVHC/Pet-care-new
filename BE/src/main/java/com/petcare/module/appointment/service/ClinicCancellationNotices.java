package com.petcare.module.appointment.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
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
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Thông báo cho khách khi phòng khám hủy lịch hẹn (Lịch hẹn#5; BR-LH-10, BR-KH-05, 08; docs/adr/0047 Q2, Q9): mẫu
 * {@code APPOINTMENT_CANCELLED_BY_CLINIC} (+{@code _APP}, V17). Gọi trong transaction của bên phát sự kiện; chỉ ghi
 * outbox ({@link NotificationApi}), ST20 gửi sau commit.
 * <p>
 * Tên tra theo lô (≤ {@value #BATCH} id mỗi lần gọi, khuôn {@code ScheduleImpactFinder}), ghép theo id; id thiếu hoặc
 * trùng là dữ liệu hỏng → {@link IllegalStateException} (rollback cả use case, như {@code AppointmentResponder}).
 * {@code NotificationApi.enqueue} không kiểm biến bắt buộc của mẫu (chỉ worker ST20 kiểm, sau commit) nên mọi giá trị
 * bắt buộc được kiểm khác {@code null} ở đây. Người nhận theo BR-TB-02:
 * <ul>
 * <li>hồ sơ có tài khoản đã xác thực → {@code IN_APP} + {@code EMAIL} tới email <b>của tài khoản</b> (hồ sơ online có
 * {@code customers.email = null});</li>
 * <li>tài khoản {@code PENDING} → coi như không có tài khoản (không truyền id của tài khoản {@code PENDING} — FK chặn
 * ST02; không gửi tới email chưa xác thực);</li>
 * <li>không tài khoản, hồ sơ có email → {@code EMAIL};</li>
 * <li>không kênh nào → không gửi, log INFO {@code APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL} (lễ tân gọi theo danh sách
 * lúc hủy hàng loạt, 06 §8 Q4).</li>
 * </ul>
 */
@Component
class ClinicCancellationNotices {

    static final int BATCH = 100;
    /** Biến thời điểm định dạng sẵn theo giờ Việt Nam (khuôn V12); ngày giờ hẹn vốn là giờ Việt Nam, không đổi zone. */
    static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    /** Màn hình lịch hẹn của khách (FE {@code /appointments}). */
    static final String LINK_URL = "/appointments";
    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do");

    private static final Logger log = LoggerFactory.getLogger(ClinicCancellationNotices.class);

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;

    ClinicCancellationNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchQueryApi branches, AccountContactApi accounts) {
        this.notifications = notifications;
        this.customers = customers;
        this.pets = pets;
        this.catalog = catalog;
        this.branches = branches;
        this.accounts = accounts;
    }

    /** Ghi outbox cho từng lịch đã hủy; danh sách rỗng thì không gọi module nào. */
    void enqueueFor(List<Appointment> cancelled, ClinicCancelCause cause) {
        if (cancelled.isEmpty()) {
            return;
        }
        Map<Long, CustomerContact> contactById = fetch(ids(cancelled, Appointment::getCustomerId),
                customers::findContacts, CustomerContact::customerId, "findContacts");
        Map<Long, PetSummary> petById = fetch(ids(cancelled, Appointment::getPetId), pets::findPets,
                PetSummary::petId, "findPets");
        Map<Long, ServiceInfo> serviceById = fetch(ids(cancelled, Appointment::getServiceId), catalog::findServices,
                ServiceInfo::serviceId, "findServices");
        Map<Long, BranchSummary> branchById = new HashMap<>();
        for (Long branchId : ids(cancelled, Appointment::getBranchId)) {
            branchById.put(branchId, branches.findBranch(branchId).orElseThrow(
                    () -> new IllegalStateException("Lịch hẹn trỏ tới chi nhánh không tồn tại: " + branchId)));
        }
        List<Long> accountIds = contactById.values().stream().map(CustomerContact::accountId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, AccountContact> accountById = fetch(accountIds, accounts::findContacts, AccountContact::accountId,
                "AccountContactApi.findContacts");

        for (Appointment appointment : cancelled) {
            CustomerContact contact = require(contactById, appointment.getCustomerId(), "hồ sơ khách");
            Map<String, Object> payload = payload(contact,
                    require(petById, appointment.getPetId(), "thú cưng").name(),
                    require(serviceById, appointment.getServiceId(), "dịch vụ").name(),
                    require(branchById, appointment.getBranchId(), "chi nhánh").name(),
                    appointment.getSlotDate().atTime(appointment.getSlotStart()), cause);
            List<NotificationRequest> requests = requests(contact,
                    contact.accountId() == null ? null : require(accountById, contact.accountId(), "tài khoản"),
                    payload);
            if (requests.isEmpty()) {
                log.info("APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL appointmentId={} customerId={}", appointment.getId(),
                        contact.customerId());
            }
            requests.forEach(notifications::enqueue);
        }
    }

    /** Biến của mẫu; mọi biến bắt buộc khác {@code null} (rollback thay vì để ST20 đánh dấu {@code FAILED}). */
    static Map<String, Object> payload(CustomerContact contact, String petName, String serviceName,
            String branchName, LocalDateTime slot, ClinicCancelCause cause) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ten_thu_cung", petName);
        payload.put("dich_vu", serviceName);
        payload.put("chi_nhanh", branchName);
        payload.put("gio_hen", SLOT_FORMAT.format(slot));
        payload.put("ly_do", cause.reason());
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho thông báo hủy lịch hẹn của khách "
                        + contact.customerId());
            }
        }
        return payload;
    }

    /** Kênh và người nhận theo BR-TB-02 (docs/adr/0047 Q2); {@code account} null khi hồ sơ không gắn tài khoản. */
    static List<NotificationRequest> requests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC_APP,
                    Channel.IN_APP, account.accountId(), null, payload, LINK_URL));
            requests.add(new NotificationRequest(NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC,
                    Channel.EMAIL, null, account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC,
                    Channel.EMAIL, null, contact.email(), payload, null));
        }
        return requests;
    }

    private static List<Long> ids(List<Appointment> appointments, Function<Appointment, Long> id) {
        Set<Long> ids = new LinkedHashSet<>();
        appointments.forEach(a -> ids.add(id.apply(a)));
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
