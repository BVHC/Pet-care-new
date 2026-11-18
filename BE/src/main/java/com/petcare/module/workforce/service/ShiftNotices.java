package com.petcare.module.workforce.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Dựng yêu cầu thông báo của module workforce (BR-NS-08, docs/adr/0138 bước 4): chỉ <b>dựng</b>, không gọi
 * {@code NotificationApi}; service tự {@code enqueue} trong transaction nghiệp vụ. Khuôn
 * {@code AppointmentNoticeTargets.requests}. Khóa payload khớp đúng {@code required_vars} của mẫu ở V40: thiếu một khóa
 * thì worker ST20 coi là lỗi và thử lại mãi ({@code NotificationTemplateRenderer.missingRequired}), nên
 * {@code ShiftNoticesTest} so khóa với mẫu thật.
 */
final class ShiftNotices {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ShiftNotices() {
    }

    /**
     * Phân ca bị quản lý hủy (BR-NS-02): {@code SHIFT_ASSIGNMENT_CANCELLED_APP} (IN_APP, tới tài khoản nhân viên) rồi
     * {@code SHIFT_ASSIGNMENT_CANCELLED} (EMAIL) nếu có email. {@code NotificationApi.enqueue} ném lỗi khi kênh EMAIL
     * không có email, nên không có email thì <b>bỏ</b> bản EMAIL chứ không truyền {@code null}. Cả hai dùng chung một
     * payload; {@code ten_nhan_vien} (lời chào, tùy chọn) chỉ có ở bản EMAIL như {@code allowed_vars} của mẫu.
     */
    static List<NotificationRequest> cancelled(Long staffId, String email, String staffName, String shiftName,
            LocalDate workDate, LocalTime startTime, LocalTime endTime, String branchName, String reason) {
        Map<String, Object> inApp = new LinkedHashMap<>();
        inApp.put("ten_ca", shiftName);
        inApp.put("ngay_lam", workDate.format(DATE));
        inApp.put("gio_bat_dau", startTime.format(TIME));
        inApp.put("gio_ket_thuc", endTime.format(TIME));
        inApp.put("chi_nhanh", branchName);
        inApp.put("ly_do", reason);

        List<NotificationRequest> requests = new ArrayList<>(2);
        requests.add(new NotificationRequest(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED_APP, Channel.IN_APP,
                staffId, null, inApp, null));
        if (email != null && !email.isBlank()) {
            Map<String, Object> mail = new LinkedHashMap<>(inApp);
            mail.put("ten_nhan_vien", staffName);
            requests.add(new NotificationRequest(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED, Channel.EMAIL,
                    null, email, mail, null));
        }
        return List.copyOf(requests);
    }
}
