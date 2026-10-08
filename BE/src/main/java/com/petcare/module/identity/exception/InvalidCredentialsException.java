package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Đăng nhập thất bại vì sai thông tin (email lạ, sai mật khẩu, sai lúc đang khóa tạm): 401 với <b>một</b> thông điệp
 * chung (BR-TK-10, identity-v1 {@code POST /auth/login}). Lớp riêng theo convention 04 §4.2:
 * <ul>
 *   <li>tiêu chí 2 — HTTP 401 ({@code UNAUTHENTICATED}), khác cả 5 exception chuẩn; vì vậy kế thừa thẳng
 *       {@link PlatformException} (docs/adr/0019 mục 3), {@code GlobalExceptionHandler.handlePlatform} map theo
 *       {@code errorCode()} nên không cần handler mới;</li>
 *   <li>tiêu chí 3 — phải <b>commit</b> bộ đếm sai / khóa tạm / email cảnh báo / audit dù request lỗi: use case khai báo
 *       {@code noRollbackFor = InvalidCredentialsException.class}.</li>
 * </ul>
 * Chỉ ném trực tiếp trong method có {@code noRollbackFor}, sau khi các lệnh ghi của nhánh đó đã xong.
 */
public class InvalidCredentialsException extends PlatformException {

    public static final String MESSAGE = "Email hoặc mật khẩu không đúng";

    public InvalidCredentialsException() {
        super(ErrorCode.UNAUTHENTICATED, MESSAGE);
    }
}
