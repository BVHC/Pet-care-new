package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Refresh token không dùng được (docs/adr/0091): không có, đã dùng, hết hạn, phiên đã hủy / hết hạn hoặc tài khoản bị
 * khóa / vô hiệu hóa. 401 với <b>một</b> thông điệp chung, không nói lý do. Lớp riêng theo convention 04 §4.2:
 * <ul>
 *   <li>tiêu chí 2 — HTTP 401 ({@code UNAUTHENTICATED}), khác cả 5 exception chuẩn; kế thừa thẳng
 *       {@link PlatformException} như {@link InvalidCredentialsException}, {@code GlobalExceptionHandler} map theo
 *       {@code errorCode()};</li>
 *   <li>tiêu chí 3 — khi phát hiện dùng lại token đã dùng, việc hủy phiên và audit phải <b>commit</b> dù request lỗi:
 *       use case khai báo {@code noRollbackFor = InvalidRefreshTokenException.class}.</li>
 * </ul>
 * Chỉ ném trực tiếp trong method có {@code noRollbackFor}; các nhánh từ chối khác ném trước mọi lệnh ghi.
 */
public class InvalidRefreshTokenException extends PlatformException {

    public static final String MESSAGE = "Refresh token không hợp lệ hoặc đã hết hạn";

    public InvalidRefreshTokenException() {
        super(ErrorCode.UNAUTHENTICATED, MESSAGE);
    }
}
