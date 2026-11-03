package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Lễ tân liên kết hộ (identity-v1 #27, UC22) nhưng không có tài khoản khách nào <b>đã kích hoạt</b> ứng với email đã gõ:
 * email lạ, tài khoản nhân viên, tài khoản khách chưa có hồ sơ, hoặc tài khoản {@code PENDING} (chưa xác thực email,
 * BR-TK-08). 404 {@code RESOURCE_NOT_FOUND} như {@code ResourceNotFoundException}, nhưng cố ý một message cố định cho cả
 * bốn nguyên nhân (không để lễ tân dò email nào là nhân viên / đang chờ xác thực) và không nhắc lại email đã gõ — khác
 * message mặc định "Không tìm thấy X #id" của lớp chung, nên lớp riêng theo tiêu chí 2 của convention 04 §4.2, cùng
 * khuôn {@link CustomerAccountNotEditableException}.
 */
public class LinkAccountNotFoundException extends PlatformException {

    public static final String MESSAGE = "Không tìm thấy tài khoản khách hàng đã kích hoạt với email này";

    public LinkAccountNotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND, MESSAGE);
    }
}
