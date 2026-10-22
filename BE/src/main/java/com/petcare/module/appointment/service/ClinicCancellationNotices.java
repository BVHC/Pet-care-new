package com.petcare.module.appointment.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Thông báo cho khách khi phòng khám hủy lịch hẹn (Lịch hẹn#5; BR-LH-10, BR-KH-05, 08; docs/adr/0052 Q2, Q9): mẫu
 * {@code APPOINTMENT_CANCELLED_BY_CLINIC} (+{@code _APP}, V17). Gọi trong transaction của bên phát sự kiện; chỉ ghi
 * outbox ({@link NotificationApi}), ST20 gửi sau commit.
 * <p>
 * Tên tra theo lô và người nhận chọn qua {@link AppointmentNoticeTargets} (dùng chung với nhắc lịch ST03, docs/adr/0056);
 * id thiếu hoặc trùng là dữ liệu hỏng → {@link IllegalStateException} (rollback cả use case, như {@code AppointmentResponder}).
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

    static final int BATCH = AppointmentNoticeTargets.BATCH;
    /** Biến thời điểm định dạng sẵn theo giờ Việt Nam (khuôn V12); ngày giờ hẹn vốn là giờ Việt Nam, không đổi zone. */
    static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    /** Màn hình lịch hẹn của khách (FE {@code /appointments}). */
    static final String LINK_URL = "/appointments";
    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "dich_vu", "chi_nhanh", "gio_hen", "ly_do");

    private static final Logger log = LoggerFactory.getLogger(ClinicCancellationNotices.class);

    private final NotificationApi notifications;
    private final AppointmentNoticeTargets targets;

    ClinicCancellationNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchQueryApi branches, AccountContactApi accounts) {
        this.notifications = notifications;
        this.targets = new AppointmentNoticeTargets(customers, pets, catalog, branches, accounts);
    }

    /** Ghi outbox cho từng lịch đã hủy; danh sách rỗng thì không gọi module nào. */
    void enqueueFor(List<Appointment> cancelled, ClinicCancelCause cause) {
        List<AppointmentNoticeTargets.Subject> subjects = cancelled.stream()
                .map(a -> new AppointmentNoticeTargets.Subject(a.getId(), a.getCustomerId(), a.getPetId(),
                        a.getServiceId(), a.getBranchId()))
                .toList();
        List<AppointmentNoticeTargets.Target> resolved = targets.resolve(subjects);
        for (int i = 0; i < cancelled.size(); i++) {
            Appointment appointment = cancelled.get(i);
            AppointmentNoticeTargets.Target target = resolved.get(i);
            Map<String, Object> payload = payload(target.contact(), target.pet().name(), target.service().name(),
                    target.branch().name(), appointment.getSlotDate().atTime(appointment.getSlotStart()), cause);
            List<NotificationRequest> requests = requests(target.contact(), target.account(), payload);
            if (requests.isEmpty()) {
                log.info("APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL appointmentId={} customerId={}", appointment.getId(),
                        target.contact().customerId());
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

    /** Kênh và người nhận theo BR-TB-02 ({@link AppointmentNoticeTargets#requests}); {@code account} null khi không có. */
    static List<NotificationRequest> requests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload) {
        return AppointmentNoticeTargets.requests(contact, account, payload,
                NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC,
                NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC_APP, LINK_URL);
    }
}
