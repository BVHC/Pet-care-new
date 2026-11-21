package com.petcare.module.workforce.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.workforce.entity.ShiftRequestType;

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

    /**
     * Vắng đột xuất được ghi nhận (BR-NS-07, BR-NS-08): {@code SHIFT_ABSENCE_RECORDED_APP} (IN_APP, tới tài khoản nhân
     * viên) rồi {@code SHIFT_ABSENCE_RECORDED} (EMAIL) nếu có email; không có email thì <b>bỏ</b> bản EMAIL (xem
     * {@link #cancelled}). Khác {@link #cancelled}: mẫu V40 của vắng <b>không có</b> {@code gio_bat_dau} /
     * {@code gio_ket_thuc} nên payload không mang giờ ca (khóa ngoài {@code allowed_vars} sẽ bị worker ST20 từ chối).
     * {@code ten_nhan_vien} chỉ có ở bản EMAIL.
     */
    static List<NotificationRequest> absence(Long staffId, String email, String staffName, String shiftName,
            LocalDate workDate, String branchName, String reason) {
        Map<String, Object> inApp = new LinkedHashMap<>();
        inApp.put("ten_ca", shiftName);
        inApp.put("ngay_lam", workDate.format(DATE));
        inApp.put("chi_nhanh", branchName);
        inApp.put("ly_do", reason);

        List<NotificationRequest> requests = new ArrayList<>(2);
        requests.add(new NotificationRequest(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED_APP, Channel.IN_APP,
                staffId, null, inApp, null));
        if (email != null && !email.isBlank()) {
            Map<String, Object> mail = new LinkedHashMap<>(inApp);
            mail.put("ten_nhan_vien", staffName);
            requests.add(new NotificationRequest(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED, Channel.EMAIL,
                    null, email, mail, null));
        }
        return List.copyOf(requests);
    }

    /**
     * Câu mô tả yêu cầu cho biến {@code mo_ta_yeu_cau} của mẫu duyệt / từ chối (V40): "nghỉ ca Ca sáng ngày 25/11/2026"
     * hoặc "đổi ca Ca sáng sang ca Ca chiều ngày 25/11/2026". Cùng câu với {@code describeRequest} của mock FE
     * ({@code workforce.mock.ts}) để BE và mock ra giống nhau. {@code switch} không có {@code default} nên thêm giá trị
     * enum là biên dịch lỗi. Tên ca là tên hiện tại; SWAP bắt buộc có tên ca đích.
     */
    static String describeRequest(ShiftRequestType type, String currentShiftName, String targetShiftName,
            LocalDate workDate) {
        String day = " ngày " + workDate.format(DATE);
        return switch (type) {
            case LEAVE -> "nghỉ ca " + currentShiftName + day;
            case SWAP -> "đổi ca " + currentShiftName + " sang ca "
                    + Objects.requireNonNull(targetShiftName, "targetShiftName") + day;
        };
    }

    /**
     * Yêu cầu bị quản lý từ chối (BR-NS-06, BR-NS-08, workforce-v1 #14): {@code SHIFT_REQUEST_REJECTED_APP} (IN_APP,
     * tới tài khoản người gửi) rồi {@code SHIFT_REQUEST_REJECTED} (EMAIL) nếu có email; không có email thì <b>bỏ</b> bản
     * EMAIL chứ không truyền {@code null} ({@code NotificationApi.enqueue} ném lỗi). Cả hai dùng chung ba khóa
     * {@code mo_ta_yeu_cau}, {@code chi_nhanh}, {@code ly_do}; {@code ten_nhan_vien} (lời chào, tùy chọn) chỉ có ở bản
     * EMAIL như {@code allowed_vars} của mẫu. Khóa lạ ngoài {@code allowed_vars} sẽ bị worker ST20 từ chối.
     */
    static List<NotificationRequest> rejected(Long requesterId, String email, String requesterName,
            String description, String branchName, String decisionNote) {
        Map<String, Object> inApp = new LinkedHashMap<>();
        inApp.put("mo_ta_yeu_cau", description);
        inApp.put("chi_nhanh", branchName);
        inApp.put("ly_do", decisionNote);
        return toRequester(NotificationTemplateCode.SHIFT_REQUEST_REJECTED_APP,
                NotificationTemplateCode.SHIFT_REQUEST_REJECTED, requesterId, email, requesterName, inApp);
    }

    /**
     * Yêu cầu được quản lý duyệt (BR-NS-06, BR-NS-08, workforce-v1 #13): {@code SHIFT_REQUEST_APPROVED_APP} (IN_APP,
     * tới tài khoản người gửi) rồi {@code SHIFT_REQUEST_APPROVED} (EMAIL) nếu có email; không có email thì <b>bỏ</b> bản
     * EMAIL (xem {@link #rejected}). Khác {@link #rejected}: mẫu duyệt <b>không có</b> {@code ly_do} nên payload chỉ mang
     * {@code mo_ta_yeu_cau} và {@code chi_nhanh} (khóa ngoài {@code allowed_vars} sẽ bị worker ST20 từ chối);
     * {@code ten_nhan_vien} chỉ có ở bản EMAIL.
     */
    static List<NotificationRequest> approved(Long requesterId, String email, String requesterName,
            String description, String branchName) {
        Map<String, Object> inApp = new LinkedHashMap<>();
        inApp.put("mo_ta_yeu_cau", description);
        inApp.put("chi_nhanh", branchName);
        return toRequester(NotificationTemplateCode.SHIFT_REQUEST_APPROVED_APP,
                NotificationTemplateCode.SHIFT_REQUEST_APPROVED, requesterId, email, requesterName, inApp);
    }

    /**
     * Cặp thông báo gửi người gửi yêu cầu: IN_APP luôn có; EMAIL chỉ khi có email ({@code NotificationApi.enqueue} ném lỗi
     * khi kênh EMAIL không có email) và thêm {@code ten_nhan_vien} (lời chào, tùy chọn trong {@code allowed_vars}).
     */
    private static List<NotificationRequest> toRequester(String appTemplate, String mailTemplate, Long requesterId,
            String email, String requesterName, Map<String, Object> inApp) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        requests.add(new NotificationRequest(appTemplate, Channel.IN_APP, requesterId, null, inApp, null));
        if (email != null && !email.isBlank()) {
            Map<String, Object> mail = new LinkedHashMap<>(inApp);
            mail.put("ten_nhan_vien", requesterName);
            requests.add(new NotificationRequest(mailTemplate, Channel.EMAIL, null, email, mail, null));
        }
        return List.copyOf(requests);
    }

    /**
     * Yêu cầu nghỉ / đổi ca mới (BR-NS-08, workforce-v1 #9): mỗi quản lý nhận một {@code SHIFT_REQUEST_SUBMITTED_APP}
     * (chỉ IN_APP: mẫu V40 không có bản EMAIL cho quản lý). {@code loai_yeu_cau} đọc được trong cả tiêu đề lẫn nội dung
     * ("Yêu cầu nghỉ ca làm việc mới", "… gửi yêu cầu đổi ca Ca sáng …"); {@code switch} không có {@code default} nên
     * thêm giá trị enum là biên dịch lỗi. Mẫu không có biến ca đích nên thông báo không nêu ca đích. Danh sách người nhận
     * rỗng thì trả rỗng (bên gọi không {@code enqueue} gì và ghi cảnh báo).
     */
    static List<NotificationRequest> submitted(Collection<Long> managerIds, String requesterName,
            ShiftRequestType type, String shiftName, LocalDate workDate, String branchName, String reason) {
        String kind = switch (type) {
            case LEAVE -> "nghỉ";
            case SWAP -> "đổi";
        };
        List<NotificationRequest> requests = new ArrayList<>(managerIds.size());
        for (Long managerId : managerIds) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ten_nhan_vien", requesterName);
            payload.put("loai_yeu_cau", kind);
            payload.put("ten_ca", shiftName);
            payload.put("ngay_lam", workDate.format(DATE));
            payload.put("chi_nhanh", branchName);
            payload.put("ly_do", reason);
            requests.add(new NotificationRequest(NotificationTemplateCode.SHIFT_REQUEST_SUBMITTED_APP, Channel.IN_APP,
                    managerId, null, payload, null));
        }
        return List.copyOf(requests);
    }
}
