package com.petcare.module.care.service;

import java.util.Set;

import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * Luồng xử lý của ST20, mỗi luồng một job. Email (docs/adr/0012): HIGH — thư có người đang chờ trên màn hình (OTP, mật
 * khẩu tạm), quét riêng để không xếp hàng sau thư hàng loạt (nhắc lịch, hủy hàng loạt); NORMAL — mọi mẫu email còn lại.
 * Thêm mẫu "người dùng đang chờ" mới thì thêm mã vào {@link #HIGH_TEMPLATES}. IN_APP (docs/adr/0014): mọi dòng kênh
 * {@code IN_APP}, giao vào bảng {@code notifications}, không qua SMTP.
 */
public enum DeliveryLane {
    HIGH,
    NORMAL,
    IN_APP;

    /** Mã mẫu email thuộc luồng HIGH; luồng NORMAL là phần bù (06 §8 Q4). Không áp cho IN_APP. */
    public static final Set<String> HIGH_TEMPLATES = Set.of(
            NotificationTemplateCode.OTP_REGISTER,
            NotificationTemplateCode.OTP_PASSWORD_RESET,
            NotificationTemplateCode.OTP_EMAIL_CHANGE,
            NotificationTemplateCode.OTP_PROFILE_LINK,
            NotificationTemplateCode.STAFF_TEMP_PASSWORD);

    /** Luồng email ({@link NotificationDispatchService}); {@link #IN_APP} do {@link InAppNotificationDeliveryService}. */
    public boolean isEmail() {
        return this != IN_APP;
    }
}
