package com.petcare.module.appointment.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.repository.AppointmentRepository.ReminderRow;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Thông báo nhắc lịch hẹn (ST03, BR-LH-12; docs/adr/0051): mẫu {@code APPOINTMENT_REMINDER} (+{@code _APP}, V18). Gọi
 * trong transaction của {@link AppointmentReminderService#remind}, sau khi đã đặt {@code reminded_at}; chỉ ghi outbox,
 * ST20 gửi sau commit. Người nhận theo BR-TB-02 qua {@link AppointmentNoticeTargets#requests}; không kênh nào thì không
 * gửi và không sinh Care Task (Q3). Mọi biến bắt buộc được kiểm khác {@code null} ở đây vì
 * {@code NotificationApi.enqueue} không kiểm (chỉ ST20 kiểm, sau commit → {@code FAILED} âm thầm).
 */
@Component
class AppointmentReminderNotices {

    /** Kết quả cho một lịch. */
    enum Outcome {
        /** Đã ghi ít nhất một dòng outbox. */
        SENT,
        /** Hồ sơ không có kênh nào (tại quầy, không email) — không gửi. */
        NO_CHANNEL,
        /** Thú đã mất mà lịch còn {@code BOOKED} (dữ liệu lệch, Q10) — không gửi. */
        PET_DECEASED
    }

    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "dich_vu", "chi_nhanh", "dia_chi_chi_nhanh",
            "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen");

    private final NotificationApi notifications;
    private final AppointmentNoticeTargets targets;

    AppointmentReminderNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            CatalogQueryApi catalog, BranchQueryApi branches, AccountContactApi accounts) {
        this.notifications = notifications;
        this.targets = new AppointmentNoticeTargets(customers, pets, catalog, branches, accounts);
    }

    Outcome enqueueFor(ReminderRow row) {
        AppointmentNoticeTargets.Target target = targets.resolve(List.of(new AppointmentNoticeTargets.Subject(
                row.getId(), row.getCustomerId(), row.getPetId(), row.getServiceId(), row.getBranchId()))).get(0);
        if (target.pet().deceased()) {
            return Outcome.PET_DECEASED;
        }
        Map<String, Object> payload = payload(target, row);
        List<NotificationRequest> requests = AppointmentNoticeTargets.requests(target.contact(), target.account(),
                payload, NotificationTemplateCode.APPOINTMENT_REMINDER,
                NotificationTemplateCode.APPOINTMENT_REMINDER_APP, ClinicCancellationNotices.LINK_URL);
        if (requests.isEmpty()) {
            return Outcome.NO_CHANNEL;
        }
        requests.forEach(notifications::enqueue);
        return Outcome.SENT;
    }

    /** Biến của mẫu V18; {@code ten_khach} không bắt buộc, còn lại phải khác {@code null}. */
    static Map<String, Object> payload(AppointmentNoticeTargets.Target target, ReminderRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", target.contact().fullName());
        payload.put("ten_thu_cung", target.pet().name());
        payload.put("dich_vu", target.service().name());
        payload.put("chi_nhanh", target.branch().name());
        payload.put("dia_chi_chi_nhanh", target.branch().address());
        payload.put("so_dien_thoai_chi_nhanh", target.branch().phone());
        payload.put("gio_hen",
                ClinicCancellationNotices.SLOT_FORMAT.format(row.getSlotDate().atTime(row.getSlotStart())));
        payload.put("ma_lich_hen", row.getCode());
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho nhắc lịch hẹn #" + row.getId());
            }
        }
        return payload;
    }
}
