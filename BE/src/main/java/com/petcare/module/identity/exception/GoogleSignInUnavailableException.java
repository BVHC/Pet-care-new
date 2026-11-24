package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Đăng nhập Google tạm không dùng được: {@code GOOGLE_CLIENT_ID} chưa cấu hình, hoặc không lấy được JWKS của Google
 * (mạng, hết giờ). 503 {@code SERVICE_UNAVAILABLE} (docs/adr/0152); khách vẫn đăng nhập bằng email và mật khẩu. Lớp riêng
 * theo convention 04 §4.2 tiêu chí 2: HTTP 503 khác cả 5 exception chuẩn; kế thừa thẳng {@link PlatformException},
 * {@code GlobalExceptionHandler} map theo {@code errorCode()} nên không cần handler mới.
 */
public class GoogleSignInUnavailableException extends PlatformException {

    public static final String MESSAGE = "Đăng nhập Google tạm thời không khả dụng. Vui lòng đăng nhập bằng email và mật khẩu";

    public GoogleSignInUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE, MESSAGE);
    }
}
