package com.petcare.module.identity.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;

/**
 * Nội dung thông báo trong app khi ADMIN khóa tài khoản (BR-QT-11, BR-QT-04; 06 §8 Q2, Q4 — docs/adr/0035): mẫu
 * {@code ACCOUNT_LOCKED_FOLLOW_UP_APP} và {@code LAST_BRANCH_MANAGER_LOCKED_APP} (V12). Hàm thuần, không đọc DB; người
 * nhận do {@link AccountLockService} chọn. Khóa payload khớp {@code allowed_vars} của V12 và không chứa từ nhạy cảm.
 */
final class AccountLockNotices {

    static final String NO_PENDING_WORK = "Không có";

    private static final Map<Role, String> ROLE_LABELS = new EnumMap<>(Map.of(
            Role.CUSTOMER, "Khách hàng",
            Role.ADMIN, "Quản trị viên",
            Role.SUPER_MANAGER, "Quản lý chuỗi",
            Role.BRANCH_MANAGER, "Quản lý chi nhánh",
            Role.RECEPTIONIST, "Lễ tân",
            Role.VET, "Bác sĩ thú y",
            Role.CARETAKER, "Nhân viên chăm sóc"));

    private static final Map<PendingWorkType, String> WORK_LINES = new EnumMap<>(Map.of(
            PendingWorkType.VISIT_IN_PROGRESS, "Lượt tiếp nhận #%d: đang thực hiện, cần gán lại",
            PendingWorkType.VISIT_WAITING, "Lượt tiếp nhận #%d: đang chờ trong hàng đợi",
            PendingWorkType.CASHIER_SHIFT_OPEN, "Ca thu ngân #%d: đang mở",
            PendingWorkType.APPOINTMENT_BOOKED, "Lịch hẹn #%d: đã đặt",
            PendingWorkType.BOARDING_ACTIVE, "Đặt chỗ lưu trú #%d: đang hiệu lực",
            PendingWorkType.ORDER_UNPAID, "Order #%d: chưa thanh toán"));

    private AccountLockNotices() {
    }

    /** Thông tin chung của người bị khóa, dùng cho mọi thông báo của một lần khóa. */
    record LockedAccount(String fullName, String email, Role role, String reason, Instant lockedAt) {}

    static String roleLabel(Role role) {
        return ROLE_LABELS.get(role);
    }

    /** Mỗi việc một dòng "- …"; rỗng → "Không có". */
    static String describe(List<PendingWorkItem> items) {
        if (items.isEmpty()) {
            return NO_PENDING_WORK;
        }
        return items.stream().map(item -> "- " + WORK_LINES.get(item.type()).formatted(item.entityId()))
                .collect(Collectors.joining("\n"));
    }

    /** {@code ACCOUNT_LOCKED_FOLLOW_UP_APP} cho một người nhận, với việc dở thuộc phạm vi của người đó. */
    static NotificationRequest followUp(Long recipientId, LockedAccount locked, List<PendingWorkItem> items) {
        return new NotificationRequest(NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP, Channel.IN_APP,
                recipientId, null, payload(locked, items), null);
    }

    /** {@code LAST_BRANCH_MANAGER_LOCKED_APP} cho một SUPER_MANAGER (BR-QT-04). */
    static NotificationRequest lastBranchManager(Long recipientId, LockedAccount locked, String branchName,
            List<PendingWorkItem> items) {
        Map<String, Object> payload = payload(locked, items);
        payload.put("ten_chi_nhanh", branchName);
        return new NotificationRequest(NotificationTemplateCode.LAST_BRANCH_MANAGER_LOCKED_APP, Channel.IN_APP,
                recipientId, null, payload, null);
    }

    private static Map<String, Object> payload(LockedAccount locked, List<PendingWorkItem> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ho_ten", locked.fullName());
        payload.put("email_tai_khoan", locked.email());
        payload.put("vai_tro", roleLabel(locked.role()));
        payload.put("ly_do", locked.reason());
        payload.put("thoi_diem", LoginAttemptService.UNLOCK_TIME_FORMAT.format(locked.lockedAt()));
        payload.put("viec_do_dang", describe(items));
        return payload;
    }
}
